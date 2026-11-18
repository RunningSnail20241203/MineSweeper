package com.local.minesweeper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

final class NoGuess {
    private final long deadline = System.nanoTime() + 1_800_000_000L;
    private final int[] covered = new int[8];
    private final ArrayList<Integer> opened = new ArrayList<>();

    static byte[] generate(Minefield opening, int first, long seed) {
        return new NoGuess().search(opening, first, new Random(seed));
    }

    static int warningRate(int cells) {
        if (cells >= 500_000) return 10;
        if (cells >= 62_500) return 15;
        if (cells >= 10_000) return 20;
        if (cells >= 3_600) return 22;
        return 25;
    }

    private boolean active() {
        return !Thread.currentThread().isInterrupted() && System.nanoTime() - deadline < 0;
    }

    private byte[] search(Minefield opening, int first, Random random) {
        for (int attempt = 0; attempt < 512 && active(); attempt++) {
            Minefield field = opening.copyOpening(true);
            opened.clear();
            if (!field.reveal(first, random, null, opened)) return null;
            if (field.state == Minefield.PLAYING && field.flags > 0) {
                for (int cell = 0; cell < field.cells.length; cell++)
                    if (field.cells[cell] == Minefield.FLAGGED) field.toggle(cell);
            }
            if (solve(field, random)) return field.numbers;
        }
        return null;
    }

    private boolean solve(Minefield field, Random random) {
        int size = field.cells.length;
        while (field.state == Minefield.PLAYING && active()) {
            if (field.flags == field.mines) {
                for (int i = 0; i < size && field.state == Minefield.PLAYING; i++) {
                    if ((i & 63) == 0 && !active()) return false;
                    if (field.cells[i] == Minefield.COVERED) reveal(field, random, i);
                }
                break;
            }
            boolean changed = false;
            for (int clue = 0; clue < size && field.state == Minefield.PLAYING; clue++) {
                if ((clue & 63) == 0 && !active()) return false;
                if (field.cells[clue] != Minefield.OPEN) continue;
                int pair = readClue(field, clue);
                int count = pair & 15, mines = pair >> 4;
                if (count > 0 && (mines == 0 || mines == count)) {
                    apply(field, random, count, mines > 0);
                    changed = true;
                }
            }
            if (field.isOver()) break;
            if (!changed && !compareClues(field, random)) return false;
        }
        return field.state == Minefield.WON;
    }

    private int readClue(Minefield field, int clue) {
        int mines = field.numbers[clue], count = 0;
        for (int offset : field.neighborOffsets(clue)) {
            int neighbor = clue + offset;
            if (field.cells[neighbor] == Minefield.COVERED) covered[count++] = neighbor;
            if (field.cells[neighbor] == Minefield.FLAGGED) mines--;
        }
        return (mines << 4) | count;
    }

    private boolean compareClues(Minefield field, Random random) {
        Constraint[] clues = new Constraint[field.cells.length];
        for (int clue = 0; clue < clues.length; clue++) {
            if ((clue & 63) == 0 && !active()) return false;
            if (field.cells[clue] != Minefield.OPEN) continue;
            int pair = readClue(field, clue);
            int count = pair & 15;
            if (count >= 2) clues[clue] = new Constraint(Arrays.copyOf(covered, count), pair >> 4);
        }
        for (int clue = 0; clue < clues.length; clue++) {
            if ((clue & 63) == 0 && !active()) return false;
            if (clues[clue] == null) continue;
            int x = clue % field.columns, y = clue / field.columns;
            for (int row = y; row <= Math.min(y + 2, field.rows - 1); row++) {
                int start = row == y ? x + 1 : Math.max(0, x - 2);
                for (int col = start; col <= Math.min(x + 2, field.columns - 1); col++) {
                    Constraint other = clues[row * field.columns + col];
                    if (other != null && overlap(field, random, clues[clue], other)) return true;
                }
            }
        }
        return false;
    }

    private boolean overlap(Minefield field, Random random, Constraint first, Constraint second) {
        int common = 0;
        for (int cell : first.cells) if (Minefield.contains(second.cells, cell)) common++;
        if (common == 0) return false;
        int onlyFirst = first.cells.length - common, onlySecond = second.cells.length - common;
        int minimum = Math.max(0, Math.max(first.mines - onlyFirst, second.mines - onlySecond));
        int maximum = Math.min(common, Math.min(first.mines, second.mines));
        if (maximum == 0 || minimum == common)
            return applyComparison(field, random, first, second, true, maximum > 0);
        if (onlyFirst > 0 && (first.mines == minimum || first.mines - maximum == onlyFirst))
            return applyComparison(field, random, first, second, false, first.mines != minimum);
        if (onlySecond > 0 && (second.mines == minimum || second.mines - maximum == onlySecond))
            return applyComparison(field, random, second, first, false, second.mines != minimum);
        return false;
    }

    private boolean applyComparison(Minefield field, Random random, Constraint first, Constraint second,
                                    boolean shared, boolean flag) {
        int count = 0;
        for (int cell : first.cells)
            if (Minefield.contains(second.cells, cell) == shared) covered[count++] = cell;
        apply(field, random, count, flag);
        return true;
    }

    private void apply(Minefield field, Random random, int count, boolean flag) {
        for (int i = 0; i < count && field.state == Minefield.PLAYING; i++) {
            int cell = covered[i];
            if (field.cells[cell] != Minefield.COVERED) continue;
            if (flag) field.toggle(cell);
            else reveal(field, random, cell);
        }
    }

    private void reveal(Minefield field, Random random, int cell) {
        opened.clear();
        field.reveal(cell, random, null, opened);
    }

    private static final class Constraint {
        final int[] cells;
        final int mines;
        Constraint(int[] cells, int mines) { this.cells = cells; this.mines = mines; }
    }
}
