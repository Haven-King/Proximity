function parseFace(card, face) {
    var result = card.deepCopy();
    var keys = Java.from(face.getKeys());

    for (var key of keys) {
        result.add(key, face.get(key).deepCopy());
    }

    return result;
}

function parseTwoSidedCard(context, card, number, options, overrides) {
    var colorsArray = new JsonArray();

    var faces = card.getAsJsonArray("card_faces");

    var front = parseFace(card, faces.get(0));
    var back = parseFace(card, faces.get(1));

    var backPath = new JsonArray();
    backPath.add("backs");
    backPath.add(number + " " + back.getAsString(["name"]).replaceAll("[^a-zA-Z0-9.\\-, ]", "_"));
    back.add(["proximity", "path"], backPath);
    back.add(["proximity", "front_face"], false);

    var frontPath = new JsonArray();
    frontPath.add("fronts");
    frontPath.add(number + " " + front.getAsString(["name"]).replaceAll("[^a-zA-Z0-9.\\-, ]", "_"));
    front.add(["proximity", "path"], frontPath);
    front.add(["proximity", "front_face"], true);

    context.submit("postInit", () => back.add(["proximity", "flipped"], front.deepCopy()));
    context.submit("postInit", () => front.add(["proximity", "flipped"], back.deepCopy()));

    return [front, back];
}

function apply(context, card, number, options, overrides) {
    if (card.has("card_faces")) {
        return parseTwoSidedCard(context, card, number, options, overrides);
    } else {
        var path = new JsonArray();
        path.add("fronts");
        path.add(number + " " + card.getAsString(["name"]).replaceAll("[^a-zA-Z0-9.\\-, ]", "_"));
        card.add(["proximity", "path"], path);

        return card;
    }
}