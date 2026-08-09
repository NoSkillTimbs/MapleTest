package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

public final class FishingLevelsHandler {

    private static final double RESPONSE_CHANCE = 0.90;
    private static final int MIN_LEVEL = 10;
    private static final int MAX_LEVEL = 99;

    private static final long GLOBAL_COOLDOWN_MS = 1_000;

    private static volatile long nextGlobalResponseMs = 0L;

    private FishingLevelsHandler() {
    }

    public static void handleChat(Character player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }

        if (!isFishingLevelsMessage(message)) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextGlobalResponseMs) {
            return;
        }

        nextGlobalResponseMs = now + GLOBAL_COOLDOWN_MS;

        CharacterMapBots.respond(player);
    }

    private static boolean isFishingLevelsMessage(String message) {
        String normalized = message
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        return normalized.matches(
                ".*\\bfishing\\s+(levels?|lvls?|lvs?|lv?)\\b.*"
        );
    }

    private static final class CharacterMapBots {

        private static void respond(Character player) {
            Collection<Character> characters =
                    player.getMap().getCharacters();

            for (Character character : characters) {

                if (character == null) {
                    continue;
                }

                if (!BotHelpers.isBot(character)) {
                    continue;
                }

                if (Math.random() > RESPONSE_CHANCE) {
                    continue;
                }

                int fishingLevel =
                        ThreadLocalRandom.current()
                                .nextInt(
                                        MIN_LEVEL,
                                        MAX_LEVEL + 1
                                );

                SocialCommands.BotSpeak(
                        character,
                        String.valueOf(fishingLevel)
                );
            }
        }
    }
}