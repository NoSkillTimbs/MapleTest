package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import java.util.concurrent.ThreadLocalRandom;

public enum BotStyle {

    NORMAL,
    HENEHOE,
    FM_MERCHANT,
    PRO,
    OLD_SCHOOL;

    public static BotStyle random() {

        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < 8)
            return HENEHOE;

        if (roll < 13)
            return FM_MERCHANT;

        if (roll < 18)
            return PRO;

        if (roll < 22)
            return OLD_SCHOOL;

        return NORMAL;
    }

}