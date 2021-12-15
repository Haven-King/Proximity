package dev.hephaestus.proximity.templates.layers.renderers;

import dev.hephaestus.proximity.api.Values;
import dev.hephaestus.proximity.util.*;
import dev.hephaestus.proximity.xml.LayerProperty;
import dev.hephaestus.proximity.xml.LayerRenderer;
import dev.hephaestus.proximity.xml.RenderableData;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

public class LayoutElementRenderer extends ParentLayerRenderer {
    private final String inLine, offLine;
    private final Function<Rectangle2D, Double> inLineSizeGetter;
    private final Function<Rectangle2D, Double> offLineSizeGetter;

    public LayoutElementRenderer(RenderableData data, String inLine, String offLine, Function<Rectangle2D, Double> inLineSizeGetter, Function<Rectangle2D, Double> offLineSizeGetter) {
        super(data);
        this.inLine = inLine;
        this.offLine = offLine;
        this.inLineSizeGetter = inLineSizeGetter;
        this.offLineSizeGetter = offLineSizeGetter;
    }

    @Override
    protected Result<Optional<Rectangles>> renderLayer(RenderableData card, RenderableData.XMLElement element, StatefulGraphics graphics, Rectangles wrap, boolean draw, Box<Float> scale, Rectangle2D bounds, List<Pair<RenderableData.XMLElement, LayerRenderer>> children) {
        int x = (element.hasAttribute("x") ? Integer.decode(element.getAttribute("x")) : 0);
        int y = (element.hasAttribute("y") ? Integer.decode(element.getAttribute("y")) : 0);
        Integer width = element.hasAttribute("width") ? Integer.decode(element.getAttribute("width")) : null;
        Integer height = element.hasAttribute("height") ? Integer.decode(element.getAttribute("height")) : null;
        ContentAlignment alignment = element.hasAttribute("alignment") ? ContentAlignment.valueOf(element.getAttribute("alignment").toUpperCase(Locale.ROOT)) : ContentAlignment.START;
        Rectangle2D outerBounds = width == null || height == null ? null : new Rectangle2D.Float(x, y, width, height);
        wrap = element.getProperty(LayerProperty.WRAP);

        List<String> errors = new ArrayList<>();

        Rectangles renderBounds;

        int tries = 1000;
        int inLine = element.getInteger(this.inLine);
        float originalScale = scale.get();
        boolean scales = this.scales(card, element);

        do {
            --tries;

            renderBounds = render(card, graphics, wrap, false, scale, children, inLine, element.getInteger(this.offLine), outerBounds, errors);
            Rectangle2D renderBoundsRectangle = renderBounds.getBounds();

            if (renderBounds.isInfinite()) {
                scale.set(scale.get() - 0.25F);
                renderBounds = new Rectangles();
                continue;
            }

            if (!renderBounds.isEmpty()) {
                if (outerBounds != null) {
                    inLine = (int) (element.getInteger(this.inLine) + switch (alignment) {
                        case START -> 0;
                        case MIDDLE -> ((this.inLineSizeGetter.apply(outerBounds) - this.inLineSizeGetter.apply(renderBoundsRectangle)) / 2);
                        case END -> (this.inLineSizeGetter.apply(outerBounds) - this.inLineSizeGetter.apply(renderBoundsRectangle));
                    });
                } else if (alignment != ContentAlignment.START) {
                    if (alignment == ContentAlignment.MIDDLE) {
                        inLine -= this.inLineSizeGetter.apply(renderBoundsRectangle) / 2;
                    } else {
                        inLine -= this.inLineSizeGetter.apply(renderBoundsRectangle);
                    }
                }
            }

            renderBounds = render(card, graphics, wrap, false, scale, children, inLine, element.getInteger(this.offLine), outerBounds, errors);
            boolean fitsWithinBounds = !renderBounds.isEmpty() && scales && (!renderBounds.isEmpty() && outerBounds != null && !renderBounds.fitsWithin(outerBounds) || !renderBounds.isEmpty() && wrap != null && !wrap.isEmpty() && renderBounds.intersects(wrap));

            if (renderBounds.isInfinite() || fitsWithinBounds) {
                scale.set(scale.get() - 1F);
                renderBounds = new Rectangles();
            } else {
                renderBounds = render(card, graphics, wrap, draw, scale, children, inLine, element.getInteger(this.offLine), outerBounds, errors);
            }
        } while ((renderBounds.isEmpty() || renderBounds.isInfinite()) && tries > 0);

        if (!errors.isEmpty()) {
            return Result.error("Error creating child factories for layer %s:\n\t%s", element.getId(), String.join("\n\t", errors));
        }

        if (renderBounds.isEmpty()) {
            scale.set(originalScale);
            return Result.of(Optional.of(Rectangles.infinity()));
        }

        return Result.of(Optional.of(renderBounds));
    }

    private Rectangles render(RenderableData card, StatefulGraphics graphics, Rectangles wrap, boolean draw, Box<Float> scale, List<Pair<RenderableData.XMLElement, LayerRenderer>> children, int inLine, int offLine, Rectangle2D outerBounds, List<String> errors) {
        int dInLine = inLine;
        Rectangles resultBounds = new Rectangles();

        for (var pair : children) {
            RenderableData.XMLElement e = pair.left();
            e.pushAttribute(this.inLine, dInLine);

            if (outerBounds != null) {
                e.pushAttribute(this.offLine, offLine);
                pair.left().setProperty(LayerProperty.BOUNDS, new Rectangle2D.Double(
                        offLine,
                        inLine,
                        this.offLineSizeGetter.apply(outerBounds).intValue(),
                        (int) (this.inLineSizeGetter.apply(outerBounds) - (dInLine - inLine)))
                );
            }

            Result<Optional<Rectangles>> result = pair.right().render(card, e, graphics, wrap, draw, scale, outerBounds);

            if (outerBounds != null) {
                e.popAttribute();
            }

            e.popAttribute();

            if (result.isError()) {
                errors.add(result.getError());
            } else if (errors.isEmpty() && result.get().isPresent()) {
                Rectangles layerBounds = result.get().get();

                if (layerBounds.isInfinite()) {
                    return layerBounds;
                } else if (!layerBounds.isEmpty()) {
                    resultBounds.addAll(layerBounds);

                    if (draw && Values.DEBUG.get(card)) {
                        graphics.push(new BasicStroke(5), Graphics2D::setStroke, Graphics2D::getStroke);
                        graphics.push(DrawingUtil.getColor(0xFFFFF00), Graphics2D::setColor, Graphics2D::getColor);

                        for (Rectangle2D rectangle : layerBounds) {
                            graphics.draw(rectangle);
                        }

                        graphics.pop(2);
                    }

                    dInLine += this.inLineSizeGetter.apply(layerBounds.getBounds());
                }
            }
        }

        if (draw && outerBounds != null &&  Values.DEBUG.get(card)) {
            graphics.push(new BasicStroke(5), Graphics2D::setStroke, Graphics2D::getStroke);
            graphics.push(DrawingUtil.getColor(0xFF00FFFF), Graphics2D::setColor, Graphics2D::getColor);
            graphics.drawRect((int) outerBounds.getX(), (int) outerBounds.getY(), (int) outerBounds.getWidth(), (int) outerBounds.getHeight());
            graphics.pop(2);
        }

        return resultBounds;
    }
}
