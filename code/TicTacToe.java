import java.util.*;

// Chapter 25 - Tic-tac-toe on an N x N board: game modeling (Game, Board,
// Player, Move, GameStatus), move validation, and an O(1) win check using
// row/column/diagonal counters.
// Run: java TicTacToe.java
public class TicTacToe {
    public static void main(String[] args) {
        Game game = new Game(3, new Player("P1", Symbol.X), new Player("P2", Symbol.O));
        int[][] moves = {{0, 0}, {1, 1}, {0, 1}, {0, 1}, {2, 2}, {0, 2}, {2, 0}, {1, 0}, {2, 1}};
        for (int[] m : moves) {
            try {
                GameStatus st = game.play(m[0], m[1]);
                System.out.println(game.lastMove() + " -> " + st);
                if (st != GameStatus.IN_PROGRESS) break;
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("rejected (" + m[0] + "," + m[1] + "): " + e.getMessage());
            }
        }
        System.out.println(game.board().render());
    }
}

enum Symbol { X, O }

enum GameStatus { IN_PROGRESS, X_WON, O_WON, DRAW }

record Player(String name, Symbol symbol) {}

record Move(Player player, int row, int col) {
    @Override
    public String toString() { return player.name() + "(" + player.symbol() + ") at " + row + "," + col; }
}

class Board {
    private final int n;
    private final Symbol[][] cells;

    Board(int n) {
        this.n = n;
        this.cells = new Symbol[n][n];
    }

    int size() { return n; }

    void place(int r, int c, Symbol s) {
        if (r < 0 || c < 0 || r >= n || c >= n) throw new IllegalArgumentException("off the board");
        if (cells[r][c] != null) throw new IllegalArgumentException("cell taken");
        cells[r][c] = s;
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        for (Symbol[] row : cells) {
            for (Symbol s : row) sb.append(s == null ? '.' : s.name().charAt(0)).append(' ');
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}

class Game {
    private final Board board;
    private final Player[] players;
    private int turn = 0;
    private int movesMade = 0;
    private GameStatus status = GameStatus.IN_PROGRESS;
    private Move lastMove;

    // O(1) win check: X adds +1, O adds -1; a line is won when |sum| == n.
    private final int[] rows, cols;
    private int diag, antiDiag;

    Game(int n, Player p1, Player p2) {
        this.board = new Board(n);
        this.players = new Player[]{p1, p2};
        this.rows = new int[n];
        this.cols = new int[n];
    }

    GameStatus play(int r, int c) {
        if (status != GameStatus.IN_PROGRESS) throw new IllegalStateException("game over");
        Player p = players[turn];
        board.place(r, c, p.symbol());          // throws if invalid; turn does not advance
        lastMove = new Move(p, r, c);
        movesMade++;

        int n = board.size();
        int d = p.symbol() == Symbol.X ? 1 : -1;
        rows[r] += d;
        cols[c] += d;
        if (r == c) diag += d;
        if (r + c == n - 1) antiDiag += d;

        if (Math.abs(rows[r]) == n || Math.abs(cols[c]) == n
                || Math.abs(diag) == n || Math.abs(antiDiag) == n) {
            status = p.symbol() == Symbol.X ? GameStatus.X_WON : GameStatus.O_WON;
        } else if (movesMade == n * n) {
            status = GameStatus.DRAW;
        }
        turn = 1 - turn;
        return status;
    }

    Move lastMove() { return lastMove; }
    Board board() { return board; }
}
