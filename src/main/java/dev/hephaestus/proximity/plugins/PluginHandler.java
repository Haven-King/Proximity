package dev.hephaestus.proximity.plugins;

import dev.hephaestus.proximity.Proximity;
import dev.hephaestus.proximity.api.json.JsonObject;
import dev.hephaestus.proximity.plugins.util.Artifact;
import dev.hephaestus.proximity.util.Box;
import dev.hephaestus.proximity.util.RemoteFileCache;
import dev.hephaestus.proximity.util.Result;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.util.*;

public class PluginHandler {
    private final Map<Artifact, Plugin> loadedPlugins = new HashMap<>();

    private Result<Plugin> loadPlugin(URI pluginUrl, TaskHandler taskHandler, boolean help) {
        Proximity.LOG.debug("Loading plugin from '{}'", pluginUrl);

        Result<URL> pluginJar;

        try {
            pluginJar = RemoteFileCache.load().getLocation(pluginUrl);
        } catch (IOException e) {
            return Result.error(e.getMessage());
        }

        if (pluginJar.isError()) {
            return pluginJar.unwrap();
        }

        ClassLoader pluginClassLoader = new PluginClassLoader(pluginJar.get());
        InputStream plugin = pluginClassLoader.getResourceAsStream("plugin.proximity.xml");

        if (plugin == null) {
            return Result.error("Plugin does not contain a plugin.proximity.xml file");
        }

        try {
            DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document document = documentBuilder.parse(plugin);
            Element root = document.getDocumentElement();
            Plugin.Builder builder = new Plugin.Builder();

            Result<Void> result = this.parseTasks(taskHandler, pluginClassLoader, root.getElementsByTagName("Tasks"));

            if (result.isError()) return result.unwrap();

            result = this.parseOptions(root.getElementsByTagName("Options"), builder, help);

            if (result.isError()) return result.unwrap();

            Proximity.LOG.debug("Successfully loaded plugin");

            return Result.of(builder.build());
        } catch (SAXException | ParserConfigurationException | IOException e) {
            return Result.error("%s: %s", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private Result<Void> parseOptions(NodeList optionsTags, Plugin.Builder builder, boolean help) {
        List<String> errors = new ArrayList<>();

        if (help) {
            System.out.println("\nAvailable Options\n");
        }

        for (int i = 0; i < optionsTags.getLength(); ++i) {
            if (optionsTags.item(i) instanceof Element optionsTag) {
                NodeList optionTags = optionsTag.getChildNodes();

                for (int j = 0; j < optionTags.getLength(); ++j) {
                    if (optionTags.item(j) instanceof Element option) {
                        String[] id = option.getAttribute("id").split("\\.");

                        if (help) {
                            System.out.printf("Key: %s%n", option.getAttribute("id"));

                            if (option.hasAttribute("default")) {
                                System.out.printf("Default: %s%n", option.getAttribute("default"));
                            }
                        }

                        switch (option.getTagName()) {
                            case "Enumeration" -> {
                                String defaultValue = option.getAttribute("default");
                                Box<Boolean> defaultValuePresent = new Box<>(false);
                                NodeList enumerationValues = option.getElementsByTagName("EnumerationValue");

                                if (help) {
                                    System.out.print("Possible Values: ");
                                }

                                for (int k = 0; k < enumerationValues.getLength(); ++k) {
                                    if (enumerationValues.item(k) instanceof Element element) {
                                        if (help) {
                                            if (k > 0) {
                                                System.out.print(", ");
                                            }

                                            System.out.print(element.getAttribute("value"));
                                        } else {
                                            defaultValuePresent.set(defaultValuePresent.get() || element.getAttribute("value").equals(defaultValue));
                                        }
                                    }
                                }

                                if (help) {
                                    System.out.println();
                                } else {
                                    if (defaultValuePresent.get()) {
                                        builder.add(object -> {
                                            JsonObject options = object.getAsJsonObject("proximity", "options");

                                            if (!options.has(id)) {
                                                options.add(id, defaultValue);
                                            }
                                        });
                                    }
                                }
                            }
                            case "ToggleOption" -> builder.add(object -> {
                                if (help) {
                                    System.out.println("Possible Values: [true|false]");
                                } else {
                                    JsonObject options = object.getAsJsonObject("proximity", "options");

                                    if (!options.has(id) && option.hasAttribute("default")) {
                                        options.add(id, Boolean.parseBoolean(option.getAttribute("default")));
                                    }
                                }
                            });
                            case "StringOption" -> builder.add(object -> {
                                JsonObject options = object.getAsJsonObject("proximity", "options");

                                if (!options.has(id) && option.hasAttribute("default")) {
                                    options.add(id, option.getAttribute("default"));
                                }
                            });
                        }

                        if (help) {
                            if (option.getUserData("comment") != null) {
                                System.out.println(option.getUserData("comment"));
                            }

                            System.out.println();
                        }
                    }
                }
            }
        }

        if (errors.isEmpty()) {
            return Result.of(null);
        } else {
            return Result.error("Error(s) parsing styles:\n\t%s", String.join("\n\t", errors));
        }
    }

    private Result<Void> parseTasks(TaskHandler taskHandler, ClassLoader pluginClassLoader, NodeList tasksTags) {
        for (int i = 0; i < tasksTags.getLength(); ++i) {
            if (tasksTags.item(i) instanceof Element tasks) {
                NodeList taskTags = tasks.getChildNodes();

                for (int j = 0; j < taskTags.getLength(); ++j) {
                    if (taskTags.item(j) instanceof Element task && taskHandler.contains(task.getTagName())) {
                        String taskName = task.getTagName();
                        TaskDefinition<?, ?> taskDefinition = taskHandler.getTaskDefinition(taskName);
                        Result<Void> result = parseTask(taskHandler, pluginClassLoader, taskDefinition, task);

                        if (result.isError()) {
                            return result;
                        }
                    }
                }
            }
        }

        return Result.of(null);
    }

    private <T> Result<Void> parseTask(TaskHandler taskHandler, ClassLoader pluginClassLoader, TaskDefinition<T, ?> taskDefinition, Element task) {
        try {
            Result<T> parsingResult = taskDefinition.parse(pluginClassLoader, task);

            if (parsingResult.isError()) {
                return parsingResult.unwrap();
            }

            if (task.hasAttribute("name")) {
                taskHandler.put(taskDefinition, task.getAttribute("name"), parsingResult.get());
            } else {
                taskHandler.put(taskDefinition, parsingResult.get());
            }
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException | InvocationTargetException | InstantiationException e) {
            return Result.error("%s: %s", e.getClass().getSimpleName(), e.getMessage());
        }

        return Result.of(null);
    }

    public synchronized Result<Plugin> loadPlugin(Artifact artifact, TaskHandler taskHandler, boolean help) {
        if (!this.loadedPlugins.containsKey(artifact)) {
            Result<URI> artifactUrl = artifact.getLatestMatchingVersionLocation();

            if (artifactUrl.isOk()) {
                Result<Plugin> result = this.loadPlugin(artifactUrl.get(), taskHandler, help);

                if (result.isOk()) {
                    this.loadedPlugins.put(artifact, result.get());
                } else {
                    return result;
                }
            } else {
                return artifactUrl.unwrap();
            }
        }

        return Result.of(this.loadedPlugins.get(artifact));
    }
}
