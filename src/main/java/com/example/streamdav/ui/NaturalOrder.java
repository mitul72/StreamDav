package com.example.streamdav.ui;

import java.util.Comparator;

/** Case-insensitive ordering that compares runs of digits by value, so "Episode 2" sorts before "Episode 10". */
final class NaturalOrder implements Comparator<String> {
    static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {
    }

    @Override
    public int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            if (isDigit(a.charAt(i)) && isDigit(b.charAt(j))) {
                int startA = i;
                int startB = j;
                while (i < a.length() && isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && isDigit(b.charAt(j))) {
                    j++;
                }
                String numberA = stripLeadingZeros(a.substring(startA, i));
                String numberB = stripLeadingZeros(b.substring(startB, j));
                // Longer digit runs are bigger numbers; equal lengths compare digit by digit.
                int result = numberA.length() != numberB.length()
                        ? Integer.compare(numberA.length(), numberB.length())
                        : numberA.compareTo(numberB);
                if (result != 0) {
                    return result;
                }
            } else {
                int result = Character.compare(Character.toLowerCase(a.charAt(i)), Character.toLowerCase(b.charAt(j)));
                if (result != 0) {
                    return result;
                }
                i++;
                j++;
            }
        }
        int result = Integer.compare(a.length() - i, b.length() - j);
        // Break remaining ties (case, leading zeros) so the order is total.
        return result != 0 ? result : a.compareTo(b);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }
}
