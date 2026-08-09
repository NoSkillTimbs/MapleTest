package soloMapling.ArtificialPlayer;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.ArtificialPlayer.BotTypes.DiceBot;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotDialogue;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.server.SoloMaplingUtilities.random;

public class BotDialogueHandler {

    private static final int CONTEXT_REROLLS = 6;
    public static final double CONTEXT_LINE_CHANCE = 0.20;

    private Character chr;

    public static class DialogueConstructor {
        private List<String> dialogue;
        private final List<Integer> emotes;
        private final List<List<Integer>> lineEmotes;
        private final long duration;

        public DialogueConstructor(
                List<String> dialogue,
                List<Integer> emotes,
                long duration) {

            this(dialogue, emotes, null, duration);
        }

        public DialogueConstructor(
                List<String> dialogue,
                List<Integer> emotes,
                List<List<Integer>> lineEmotes,
                long duration) {

            this.dialogue = dialogue;
            this.emotes = emotes;
            this.lineEmotes = lineEmotes;
            this.duration = duration;
        }

        private void setDialogue(List<String> dialogue) {
            this.dialogue = dialogue;
        }

        public List<String> getDialogue() {
            return dialogue;
        }

        public String getDialogue(int index) {
            return dialogue.get(index);
        }

        public Integer getEmote() {
            if (emotes == null || emotes.isEmpty()) {
                return 0;
            }

            return emotes.get(random.nextInt(emotes.size()));
        }

        public Integer getEmoteForIndex(int index) {
            if (lineEmotes != null
                    && index >= 0
                    && index < lineEmotes.size()) {

                List<Integer> override = lineEmotes.get(index);

                if (override != null && !override.isEmpty()) {
                    return override.get(
                            random.nextInt(override.size())
                    );
                }
            }

            return getEmote();
        }

        public List<Integer> getEmotes() {
            return emotes;
        }

        private long getDuration() {
            return duration;
        }
    }

    public BotDialogueHandler(Character chr) {
        this.chr = chr;
    }

    public void executeBotFlavorDialogue(
            String dialogueNodeName,
            BotSM botSM) {

        runBotFlavorDialogue(
                botSM.getChr(),
                getDialogueCon(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName
                )
        );
    }

    public void executeBotContextDialogue(
            String dialogueNodeName,
            BotSM botSM) {

        runBotContextFlavorDialogue(
                botSM.getChr(),
                getDialogueCon(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName
                )
        );
    }

    public void executeBotContextDialogue(
            String dialogueNodeName,
            BotSM botSM,
            Character player) {

        runBotContextFlavorDialogue(
                botSM.getChr(),
                getDialogueCon(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName
                ),
                player
        );
    }

    public void executeBotContextDialogue(
            String dialogueNodeName,
            BotSM botSM,
            Character player,
            double contextChance) {

        runBotContextFlavorDialogue(
                botSM.getChr(),
                getDialogueCon(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName
                ),
                player,
                contextChance
        );
    }

    public void executeBotDialogueWithReplacementStrings(
            String dialogueNodeName,
            Map<String, String> replacements,
            BotSM botSM) {

        runBotDialogue(
                botSM.getChr(),
                getDialogueConWithReplacedStrings(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName,
                        replacements
                )
        );
    }

    public void executeBotDialogue(
            String dialogueNodeName,
            BotSM botSM) {

        runBotDialogue(
                botSM.getChr(),
                getDialogueCon(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName
                )
        );
    }

    public void listOptions(Character player, BotSM botSM) {
        if (botSM instanceof DiceBot) {
            ((DiceBot) botSM).displayCommands(player);
        } else {
            botSM.displayCommands(player);
        }
    }

    public static Map<String, Object> readDialogueYaml(
            String dialoguePack,
            String dialogueType,
            String dialogueNode) {

        String basePath =
                "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/";

        String filePath = basePath + dialoguePack;

        try (FileReader fileReader = new FileReader(filePath)) {
            YamlReader reader = new YamlReader(fileReader);

            Map<String, Object> root =
                    (Map<String, Object>) reader.read();

            if (root == null) {
                return null;
            }

            Object rawBotType = root.get(dialogueType);

            if (!(rawBotType instanceof Map)) {
                return null;
            }

            Map<String, Object> botTypeNode =
                    (Map<String, Object>) rawBotType;

            Object rawDialogueNode =
                    botTypeNode.get(dialogueNode);

            if (!(rawDialogueNode instanceof Map)) {
                return null;
            }

            return (Map<String, Object>) rawDialogueNode;

        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static DialogueConstructor getDialogueCon(
            String botTypeDialoguePath,
            String botType,
            String dialogueNodeName) {

        Map<String, Object> dialogMap =
                readDialogueYaml(
                        botTypeDialoguePath,
                        botType,
                        dialogueNodeName
                );

        if (dialogMap == null) {
            return null;
        }

        List<String> textList = new ArrayList<>();
        List<List<Integer>> lineEmotes = new ArrayList<>();

        boolean hasLineEmotes =
                parseTextEntries(
                        dialogMap.get("text"),
                        textList,
                        lineEmotes
                );

        List<Integer> emotes =
                parseEmotes(dialogMap.get("emote"));

        long duration =
                convertToInt(dialogMap.get("wait")) * 1000L;

        return new DialogueConstructor(
                textList,
                emotes,
                hasLineEmotes ? lineEmotes : null,
                duration
        );
    }

    private static boolean parseTextEntries(
            Object raw,
            List<String> textOut,
            List<List<Integer>> emotesOut) {

        if (!(raw instanceof List)) {
            return false;
        }

        boolean hasOverrides = false;

        for (Object entry : (List<?>) raw) {
            if (entry instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) entry;

                Object line =
                        map.containsKey("line")
                                ? map.get("line")
                                : map.get("text");

                textOut.add(
                        line == null
                                ? ""
                                : line.toString()
                );

                Object emote = map.get("emote");

                if (emote != null) {
                    emotesOut.add(parseEmotes(emote));
                    hasOverrides = true;
                } else {
                    emotesOut.add(null);
                }

            } else {
                textOut.add(
                        entry == null
                                ? ""
                                : entry.toString()
                );

                emotesOut.add(null);
            }
        }

        return hasOverrides;
    }

    public static DialogueConstructor getDialogueConWithReplacedStrings(
            String botTypeDialoguePath,
            String botType,
            String dialogueNodeName,
            Map<String, String> replacements) {

        DialogueConstructor dialogue =
                getDialogueCon(
                        botTypeDialoguePath,
                        botType,
                        dialogueNodeName
                );

        if (dialogue == null) {
            return null;
        }

        dialogue.setDialogue(
                replaceStrings(
                        dialogue.getDialogue(),
                        replacements
                )
        );

        return dialogue;
    }

    public static List<String> replaceStrings(
            List<String> inputList,
            Map<String, String> replacements) {

        if (inputList == null || replacements == null) {
            throw new IllegalArgumentException(
                    "Input list and replacements map cannot be null"
            );
        }

        List<String> result = new ArrayList<>(
                inputList.size()
        );

        for (String text : inputList) {
            String replaced = text;

            for (Map.Entry<String, String> entry
                    : replacements.entrySet()) {

                replaced = replaced.replace(
                        entry.getKey(),
                        entry.getValue()
                );
            }

            result.add(replaced);
        }

        return result;
    }

    public static String getRandomDialogueLine(
            BotSM botSM,
            String dialogueNodeName) {

        DialogueConstructor dialogue =
                getDialogueCon(
                        botSM.dialoguePath,
                        botSM.botType,
                        dialogueNodeName
                );

        if (dialogue == null
                || dialogue.getDialogue().isEmpty()) {
            return null;
        }

        List<String> lines = dialogue.getDialogue();

        return lines.get(
                random.nextInt(lines.size())
        );
    }

    public static String getRandomResolvedLine(
            BotSM botSM,
            String node) {

        return getRandomResolvedLine(
                botSM,
                node,
                null
        );
    }

    public static String getRandomResolvedLine(
            BotSM botSM,
            String node,
            Character player) {

        return getRandomResolvedLine(
                botSM.dialoguePath,
                botSM.botType,
                node,
                botSM.getChr(),
                player
        );
    }

    public static String getRandomResolvedLine(
            String dialoguePath,
            String botType,
            String node,
            Character speaker,
            Character player) {

        DialogueConstructor dialogue =
                getDialogueCon(
                        dialoguePath,
                        botType,
                        node
                );

        if (dialogue == null
                || dialogue.getDialogue().isEmpty()) {
            return null;
        }

        List<String> lines =
                dialogue.getDialogue();

        int tries =
                Math.min(
                        CONTEXT_REROLLS,
                        lines.size()
                );

        for (int attempt = 0; attempt < tries; attempt++) {
            String raw =
                    lines.get(
                            random.nextInt(lines.size())
                    );

            Optional<String> resolved =
                    resolveLine(
                            raw,
                            speaker,
                            player
                    );

            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        for (String line : lines) {
            if (!DialogueContextResolver.hasTokens(line)) {
                return line;
            }
        }

        return null;
    }

    public static void runBotDialogue(
            Character character,
            DialogueConstructor dialogue) {

        if (dialogue == null) {
            return;
        }

        runDialogue(
                character,
                dialogue,
                dialogue.getDialogue(),
                dialogue.getEmote()
        );
    }

    public static void runBotFlavorDialogue(
            Character character,
            DialogueConstructor dialogue) {

        if (dialogue == null
                || dialogue.getDialogue().isEmpty()) {
            return;
        }

        int index =
                random.nextInt(
                        dialogue.getDialogue().size()
                );

        runDialogue(
                character,
                dialogue,
                Collections.singletonList(
                        dialogue.getDialogue().get(index)
                ),
                dialogue.getEmoteForIndex(index)
        );
    }

    public static void runBotContextFlavorDialogue(
            Character character,
            DialogueConstructor dialogue) {

        runBotContextFlavorDialogue(
                character,
                dialogue,
                null
        );
    }

    public static void runBotContextFlavorDialogue(
            Character character,
            DialogueConstructor dialogue,
            Character player) {

        if (dialogue == null
                || dialogue.getDialogue().isEmpty()) {
            return;
        }

        List<String> lines =
                dialogue.getDialogue();

        int tries =
                Math.min(
                        CONTEXT_REROLLS,
                        lines.size()
                );

        for (int attempt = 0; attempt < tries; attempt++) {
            int index =
                    random.nextInt(lines.size());

            Optional<String> resolved =
                    resolveLine(
                            lines.get(index),
                            character,
                            player
                    );

            if (resolved.isPresent()) {
                runDialogue(
                        character,
                        dialogue,
                        Collections.singletonList(
                                resolved.get()
                        ),
                        dialogue.getEmoteForIndex(index)
                );

                return;
            }
        }

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            if (!DialogueContextResolver.hasTokens(line)) {
                runDialogue(
                        character,
                        dialogue,
                        Collections.singletonList(line),
                        dialogue.getEmoteForIndex(index)
                );

                return;
            }
        }
    }

    public static void runBotContextFlavorDialogue(
            Character character,
            DialogueConstructor dialogue,
            Character player,
            double contextChance) {

        if (dialogue == null
                || dialogue.getDialogue().isEmpty()) {
            return;
        }

        List<String> lines =
                dialogue.getDialogue();

        List<Integer> contextLines =
                new ArrayList<>();

        List<Integer> plainLines =
                new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (DialogueContextResolver.hasTokens(lines.get(i))) {
                contextLines.add(i);
            } else {
                plainLines.add(i);
            }
        }

        boolean preferContext =
                random.nextDouble() < contextChance;

        List<Integer> preferred =
                preferContext
                        ? contextLines
                        : plainLines;

        List<Integer> fallback =
                preferContext
                        ? plainLines
                        : contextLines;

        if (emitOneFrom(
                character,
                dialogue,
                lines,
                preferred,
                player)) {
            return;
        }

        emitOneFrom(
                character,
                dialogue,
                lines,
                fallback,
                player
        );
    }

    private static boolean emitOneFrom(
            Character character,
            DialogueConstructor dialogue,
            List<String> lines,
            List<Integer> pool,
            Character player) {

        if (pool == null || pool.isEmpty()) {
            return false;
        }

        int tries =
                Math.min(
                        CONTEXT_REROLLS,
                        pool.size()
                );

        for (int attempt = 0; attempt < tries; attempt++) {
            int index =
                    pool.get(
                            random.nextInt(pool.size())
                    );

            Optional<String> resolved =
                    resolveLine(
                            lines.get(index),
                            character,
                            player
                    );

            if (!resolved.isPresent()) {
                continue;
            }

            runDialogue(
                    character,
                    dialogue,
                    Collections.singletonList(
                            resolved.get()
                    ),
                    dialogue.getEmoteForIndex(index)
            );

            return true;
        }

        for (Integer index : pool) {
            if (index == null
                    || index < 0
                    || index >= lines.size()) {
                continue;
            }

            Optional<String> resolved =
                    resolveLine(
                            lines.get(index),
                            character,
                            player
                    );

            if (!resolved.isPresent()) {
                continue;
            }

            runDialogue(
                    character,
                    dialogue,
                    Collections.singletonList(
                            resolved.get()
                    ),
                    dialogue.getEmoteForIndex(index)
            );

            return true;
        }

        return false;
    }

    private static Optional<String> resolveLine(
            String line,
            Character speaker,
            Character player) {

        if (line == null) {
            return Optional.empty();
        }

        if (!DialogueContextResolver.hasTokens(line)) {
            return Optional.of(line);
        }

        return DialogueContextResolver.fill(
                line,
                speaker,
                player
        );
    }

    private static void runDialogue(
            Character character,
            DialogueConstructor dialogue,
            List<String> textToShow,
            int emote) {

        if (dialogue == null
                || textToShow == null
                || textToShow.isEmpty()) {
            return;
        }

        BotDialogue(
                character,
                textToShow
        );

        BotEmote(
                character,
                emote
        );

        BotHelpers.blockingSleep(
                dialogue.getDuration()
        );
    }

    private static List<Integer> parseEmotes(Object value) {
        if (value instanceof List) {
            List<Integer> result =
                    new ArrayList<>();

            for (Object item : (List<?>) value) {
                result.add(
                        convertToInt(item)
                );
            }

            return result;
        }

        return Collections.singletonList(
                convertToInt(value)
        );
    }

    private static int convertToInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        if (value instanceof String) {
            try {
                return Integer.parseInt(
                        (String) value
                );
            } catch (NumberFormatException ignored) {
            }
        }

        return 0;
    }
}

