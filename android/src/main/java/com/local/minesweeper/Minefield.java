package com.local.minesweeper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

final class Minefield {
    static final int MIN_CUSTOM_SIZE = 2, MAX_CUSTOM_SIZE = 1000;
    static final byte COVERED = 0, OPEN = 1, FLAGGED = 2, MINE = -1;
    static final int READY = 0, PLAYING = 1, WON = 2, LOST = 3;
    final int columns, rows, mines, preset;
    final boolean noGuess;
    final byte[] cells, numbers;
    private final int[][] offsetsByEdge;
    int mineRate, state, opened, flags, exploded = -1;

    Minefield(int columns, int rows, int mines, int preset, boolean noGuess, int mineRate) {
        this(columns, rows, mines, preset, noGuess, mineRate, makeOffsets(columns));
    }

    private Minefield(int columns, int rows, int mines, int preset, boolean noGuess, int mineRate,
                      int[][] offsetsByEdge) {
        this.columns = columns;
        this.rows = rows;
        this.mines = mines;
        this.preset = preset;
        this.noGuess = noGuess;
        this.mineRate = mineRate;
        this.offsetsByEdge = offsetsByEdge;
        cells = new byte[columns * rows];
        numbers = new byte[cells.length];
    }

    Minefield copyOpening(boolean verification) {
        Minefield copy = new Minefield(columns, rows, mines, preset, !verification && noGuess,
                mineRate, offsetsByEdge);
        System.arraycopy(cells, 0, copy.cells, 0, cells.length);
        copy.flags = flags;
        return copy;
    }

    boolean isOver() { return state == WON || state == LOST; }

    static int minesForRate(int columns, int rows, int rate) {
        int size = columns * rows;
        return Math.max(1, Math.min(size - 1, (size * rate + 50) / 100));
    }

    static int rateForMines(int columns, int rows, int mines) {
        int size = columns * rows;
        return Math.max(1, Math.min(99, (mines * 100 + size / 2) / size));
    }

    int[] neighborOffsets(int cell) {
        int x = cell % columns;
        int edges = 0;
        if (x == 0) edges |= 1;
        if (x == columns - 1) edges |= 2;
        if (cell < columns) edges |= 4;
        if (cell >= cells.length - columns) edges |= 8;
        return offsetsByEdge[edges];
    }

    boolean canReveal(int cell) {
        if (isOver() || cells[cell] == FLAGGED) return false;
        if (cells[cell] == COVERED) return true;
        int correctCount = 0, covered = 0;
        for (int offset : neighborOffsets(cell)) {
            int neighbor = cell + offset;
            if (cells[neighbor] == FLAGGED) correctCount++;
            if (cells[neighbor] == COVERED) covered++;
        }
        return numbers[cell] > 0 && covered > 0 && correctCount == numbers[cell];
    }

    boolean canFlag(int cell) {
        if (isOver()) return false;
        if (cells[cell] != OPEN) return true;
        int unopen = 0, covered = 0;
        for (int offset : neighborOffsets(cell)) {
            int neighbor = cell + offset;
            if (cells[neighbor] != OPEN) unopen++;
            if (cells[neighbor] == COVERED) covered++;
        }
        return covered > 0 && unopen == numbers[cell];
    }

    boolean flag(int cell, List<Integer> changed) {
        if (!canFlag(cell)) return false;
        if (cells[cell] == OPEN) {
            for (int offset : neighborOffsets(cell)) {
                int neighbor = cell + offset;
                if (cells[neighbor] == COVERED) {
                    toggle(neighbor);
                    changed.add(neighbor);
                }
            }
        } else {
            toggle(cell);
            changed.add(cell);
        }
        return true;
    }

    void toggle(int cell) {
        if (cells[cell] == COVERED) {
            cells[cell] = FLAGGED;
            flags++;
        } else if (cells[cell] == FLAGGED) {
            cells[cell] = COVERED;
            flags--;
        }
    }

    boolean reveal(int cell, Random random, byte[] verifiedLayout, List<Integer> changed) {
        if (!canReveal(cell)) return false;
        if (state == READY) {
            if (noGuess) {
                if (verifiedLayout == null || verifiedLayout.length != cells.length || verifiedLayout[cell] == MINE) return false;
                System.arraycopy(verifiedLayout, 0, numbers, 0, numbers.length);
            } else place(cell, random);
            state = PLAYING;
        }
        if (cells[cell] == OPEN) {
            for (int offset : neighborOffsets(cell)) {
                int neighbor = cell + offset;
                if (cells[neighbor] == COVERED) open(neighbor, changed);
                if (state == LOST) break;
            }
        } else open(cell, changed);
        if (state == PLAYING && opened == cells.length - mines) {
            state = WON;
            for (int i = 0; i < cells.length; i++)
                if (numbers[i] == MINE) cells[i] = FLAGGED;
            flags = mines;
        }
        return true;
    }

    private void place(int first, Random random) {
        int[] firstOffsets = neighborOffsets(first);
        boolean wideSafe = cells.length - 1 - firstOffsets.length >= mines;
        int[] locations = new int[cells.length];
        int size = 0;
        for (int i = 0; i < cells.length; i++) {
            if (i == first) continue;
            if (wideSafe && contains(firstOffsets, i - first)) continue;
            locations[size++] = i;
        }
        for (int i = 0; i < mines; i++) {
            int pick = i + random.nextInt(size - i);
            numbers[locations[pick]] = MINE;
            locations[pick] = locations[i];
        }
        for (int cell = 0; cell < cells.length; cell++) {
            if (numbers[cell] != MINE) continue;
            for (int offset : neighborOffsets(cell)) {
                int near = cell + offset;
                if (numbers[near] != MINE) numbers[near]++;
            }
        }
    }

    private void open(int cell, List<Integer> changed) {
        if (numbers[cell] == MINE) {
            state = LOST;
            exploded = cell;
            return;
        }
        revealSafe(cell, changed);
        if (numbers[cell] != 0) return;
        ArrayDeque<Integer> empty = new ArrayDeque<>();
        empty.add(cell);
        while (!empty.isEmpty()) {
            int current = empty.removeFirst();
            for (int offset : neighborOffsets(current)) {
                int neighbor = current + offset;
                if (cells[neighbor] != COVERED) continue;
                revealSafe(neighbor, changed);
                if (numbers[neighbor] == 0) empty.add(neighbor);
            }
        }
    }

    private void revealSafe(int cell, List<Integer> changed) {
        cells[cell] = OPEN;
        opened++;
        changed.add(cell);
    }

    private static int[][] makeOffsets(int columns) {
        int[][] result = new int[16][];
        int[] buffer = new int[8];
        for (int edges = 0; edges < result.length; edges++) {
            int left = (edges & 1) == 0 ? -1 : 0, right = (edges & 2) == 0 ? 1 : 0;
            int top = (edges & 4) == 0 ? -1 : 0, bottom = (edges & 8) == 0 ? 1 : 0;
            int count = 0;
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++)
                    if (x != 0 || y != 0) buffer[count++] = y * columns + x;
            result[edges] = Arrays.copyOf(buffer, count);
        }
        return result;
    }

    static boolean contains(int[] cells, int value) {
        for (int cell : cells) if (cell == value) return true;
        return false;
    }

    void write(DataOutputStream out) throws IOException {
        out.writeInt(columns);
        out.writeInt(rows);
        out.writeInt(mines);
        out.writeInt(preset);
        out.writeBoolean(noGuess);
        out.writeInt(state);
        out.writeInt(opened);
        out.writeInt(flags);
        out.writeInt(exploded);
        out.write(cells);
        out.write(numbers);
        // Version 1 saves may end after the board arrays.
        if (mineRate > 0) out.writeByte(mineRate);
    }

    static Minefield read(DataInputStream in) throws IOException {
        int columns = in.readInt(), rows = in.readInt(), mines = in.readInt(), preset = in.readInt();
        int min = preset == 3 ? MIN_CUSTOM_SIZE : 5;
        int max = preset == 3 ? MAX_CUSTOM_SIZE : 100;
        if (columns < min || columns > max || rows < min || rows > max || mines < 1 || mines >= columns * rows
                || preset < 0 || preset > 3) throw new IOException("Invalid board settings");
        Minefield field = new Minefield(columns, rows, mines, preset, in.readBoolean(), 0);
        field.state = in.readInt();
        field.opened = in.readInt();
        field.flags = in.readInt();
        field.exploded = in.readInt();
        in.readFully(field.cells);
        in.readFully(field.numbers);
        if (field.state < READY || field.state > LOST || field.opened < 0 || field.opened > field.cells.length - mines
                || field.flags < 0 || field.flags > field.cells.length || field.exploded < -1
                || field.exploded >= field.cells.length) throw new IOException("Invalid board state");
        for (int i = 0; i < field.cells.length; i++)
            if (field.cells[i] < COVERED || field.cells[i] > FLAGGED || field.numbers[i] < MINE || field.numbers[i] > 8)
                throw new IOException("Invalid cell");
        if (preset == 3) {
            int rate = in.read();
            if (rate >= 1 && rate <= 99 && mines == minesForRate(columns, rows, rate)) field.mineRate = rate;
        }
        return field;
    }
}
