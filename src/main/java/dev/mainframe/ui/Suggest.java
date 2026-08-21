package dev.mainframe.ui;

import java.util.Collection;

/** Finds the name a user probably meant, so a typo never dead-ends. */
public final class Suggest {

    private Suggest() {}

    /** The closest candidate to {@code typed}, or null when nothing is close enough. */
    public static String closest(String typed, Collection<String> candidates) {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        int allowed = Math.max(2, typed.length() / 3 + 1);
        for (String candidate : candidates) {
            int score = distance(typed.toLowerCase(), candidate.toLowerCase());
            // A shared prefix or containment is a strong signal even when the edit
            // distance is large, e.g. "ind" for "index-build".
            if (candidate.toLowerCase().startsWith(typed.toLowerCase())) score = Math.min(score, 1);
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        return bestScore <= allowed ? best : null;
    }

    public static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
