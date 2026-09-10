package dev.deskcam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A script, read before any of it runs.
 *
 * One verb per line. No branching, no variables, no computation, no labels. This is
 * deliberately not a language: there is no evaluator here, only a dispatcher, and a tape
 * with no ranges and no step rules cannot make a wrong step as easy to write as a right
 * one. `BRACKET base=1/240 stops=4` leaves that knowledge where it already lives.
 *
 * The whole tape is parsed before the first step runs, so a verb nobody knows or a
 * parameter nobody parses costs nothing: it is a 400 with the line number, and the camera
 * was never touched. Card 57.
 *
 * The lineage is DigitaScript, which the Kodak DC290 ran in 1999. What is taken from it:
 * a status for every command, comments, capability checking before execution, and a script
 * that is a text file the caller can read back and resubmit. What is left: goto and
 * markers, typed variables, in-camera file input and output, and the commands that drove
 * an LCD. The agent is the intelligence; this is the execution record.
 */
final class Tape {

    private Tape() { }

    /** The verbs, and the endpoint each one is. */
    private static final Map<String, String> VERBS = new LinkedHashMap<>();

    /** The one verb that is not an endpoint. */
    static final String WAIT = "WAIT";

    static {
        VERBS.put("SNAP", "/api/still");
        VERBS.put("FRAME", "/api/frame");
        VERBS.put("RAW", "/api/raw");
        VERBS.put("BURST", "/api/burst");
        VERBS.put("BRACKET", "/api/bracket");
        VERBS.put("FOCUSSWEEP", "/api/focussweep");
        VERBS.put("WALK", "/api/walk");
        VERBS.put("FOCUSHUNT", "/api/focushunt");
        VERBS.put("SET", "/api/set");
        VERBS.put("RESET", "/api/reset");
        VERBS.put("AF", "/api/af");
        VERBS.put("STATUS", "/api/status");
        VERBS.put(WAIT, null);
    }

    /**
     * The most steps one tape may hold.
     *
     * A script holds the camera for its duration, so its length is how long everything
     * else is refused. Two hundred stills is already twenty minutes of that, and a tape
     * longer than this is a loop the caller should be writing as several scripts.
     */
    static final int MAX_STEPS = 200;

    /** The longest a single WAIT may be, in milliseconds. */
    static final long MAX_WAIT_MS = 60_000;

    /**
     * Whether the parser knows a parameter name.
     *
     * Handed in rather than reached for, so this class knows nothing about the camera and
     * a workstation can read a tape with no Android on the classpath. Params answers it in
     * the app; a test answers it with a set of names.
     */
    interface Names {
        boolean knows(String name);
    }

    /** One line of the tape, ready to run. */
    static final class Step {
        /** Its position in the tape, from 0. */
        final int index;
        /** The line of the submitted text it came from, from 1, for an error message. */
        final int line;
        /** The verb as the table spells it, in upper case. */
        final String verb;
        /** The endpoint it calls, or null for WAIT. */
        final String path;
        /** Its parameters, exactly as any other request would receive them. */
        final Map<String, String> params;
        /** The milliseconds a WAIT waits. Zero for everything else. */
        final long waitMs;

        Step(int index, int line, String verb, String path, Map<String, String> params,
             long waitMs) {
            this.index = index;
            this.line = line;
            this.verb = verb;
            this.path = path;
            this.params = params;
            this.waitMs = waitMs;
        }
    }

    /** The verbs, in the order they are documented, for /api/help and for an error. */
    static List<String> verbs() {
        return Collections.unmodifiableList(new ArrayList<>(VERBS.keySet()));
    }

    /**
     * Reads a tape, or refuses the whole thing.
     *
     * @throws IllegalArgumentException with the line number and what is wrong with it
     */
    static List<Step> parse(String text, Names names) {
        List<Step> steps = new ArrayList<>();
        if (text == null) text = "";
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i].replace('\r', ' ').trim();
            // A comment is a whole line. Not a trailing one: a value is allowed to hold
            // any character the parser accepts, and a tape is written by a machine that
            // does not need to annotate half a line.
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] tokens = line.split("\\s+");
            String verb = tokens[0].toUpperCase(Locale.US);
            if (!VERBS.containsKey(verb)) {
                throw new IllegalArgumentException("line " + lineNumber + ": '" + tokens[0]
                        + "' is not a verb. The verbs are " + String.join(", ", VERBS.keySet())
                        + ".");
            }
            if (steps.size() >= MAX_STEPS) {
                throw new IllegalArgumentException("line " + lineNumber + ": a tape holds at "
                        + "most " + MAX_STEPS + " steps, because it holds the camera for its "
                        + "whole duration. Send it as several scripts.");
            }

            if (verb.equals(WAIT)) {
                steps.add(new Step(steps.size(), lineNumber, verb, null,
                        Collections.emptyMap(), waitOf(tokens, lineNumber)));
                continue;
            }

            Map<String, String> params = new LinkedHashMap<>();
            for (int t = 1; t < tokens.length; t++) {
                int eq = tokens[t].indexOf('=');
                if (eq < 1) {
                    throw new IllegalArgumentException("line " + lineNumber + ": '" + tokens[t]
                            + "' is not a parameter. Everything after a verb is name=value, "
                            + "the same names every endpoint takes; see /api/help.");
                }
                String name = tokens[t].substring(0, eq).trim().toLowerCase(Locale.US);
                // The name is checked now and the value when the step runs. A name is
                // knowable without a camera; a value depends on what this device can do.
                if (!names.knows(name)) {
                    throw new IllegalArgumentException("line " + lineNumber
                            + ": unknown parameter '" + name + "'; see /api/help.");
                }
                if (params.put(name, tokens[t].substring(eq + 1).trim()) != null) {
                    throw new IllegalArgumentException("line " + lineNumber + ": '" + name
                            + "' is given twice on one line, so which one is meant is a guess.");
                }
            }
            steps.add(new Step(steps.size(), lineNumber, verb, VERBS.get(verb), params, 0));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("the tape holds no steps. One verb per line, "
                    + "'#' starts a comment; the verbs are " + String.join(", ", VERBS.keySet())
                    + ".");
        }
        return steps;
    }

    private static long waitOf(String[] tokens, int lineNumber) {
        if (tokens.length != 2) {
            throw new IllegalArgumentException("line " + lineNumber + ": WAIT takes one number "
                    + "of milliseconds, e.g. WAIT 500. It is for waiting on something that is "
                    + "not a capture, such as the LED reaching a steady temperature after "
                    + "torch=45; every capture verb has settle= for its own waiting.");
        }
        long ms;
        try {
            ms = Long.parseLong(tokens[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + lineNumber + ": '" + tokens[1]
                    + "' is not a whole number of milliseconds.");
        }
        if (ms < 0 || ms > MAX_WAIT_MS) {
            throw new IllegalArgumentException("line " + lineNumber + ": WAIT takes 0 to "
                    + MAX_WAIT_MS + " milliseconds, and was given " + ms + ".");
        }
        return ms;
    }
}
