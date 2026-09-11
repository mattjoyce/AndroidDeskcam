package dev.deskcam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What somebody is pointing at, kept where it sits on the sensor.
 *
 * The bench tool is where a person and an agent look at the same thing (D18), and this is
 * the channel that lets either of them say "this part, here". The person's half of it
 * needs no channel at all: zoom, cx, cy and focus_box are already in /api/status, so an
 * agent reads what the person is framing for free. This is the other direction.
 *
 * Every mark is stored in SENSOR coordinates and served in the coordinates of the picture
 * the caller sees, the same ones cx, cy and focusbox use. That is the whole design. Store a
 * mark the way it was named and it moves the day somebody remounts the phone and sets
 * rotate=180, because those coordinates describe the picture and not the thing on the
 * bench. Card 71.
 *
 * Marks live in memory and die with the service. The phone stores no pixels (D18), and a
 * mark is a sentence about a moment at the bench rather than a record to keep.
 */
public final class Marks {

    /** Enough for a crowded board, few enough that an agent in a loop cannot fill a phone. */
    public static final int MAX = 200;

    /** One mark, in sensor coordinates. */
    public static final class Mark {
        final int id;
        final boolean box;
        final float cx, cy, w, h;
        final String label;
        final String by;
        final long at;

        Mark(int id, boolean box, float cx, float cy, float w, float h, String label, String by) {
            this.id = id;
            this.box = box;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.label = label;
            this.by = by;
            this.at = System.currentTimeMillis();
        }
    }

    private final List<Mark> marks = new ArrayList<>();
    private int nextId = 1;

    /**
     * Adds a mark named in the coordinates of the seen picture, storing it on the sensor.
     *
     * A quarter turn trades the width for the height, for the same reason Geom.box does.
     */
    public synchronized Mark add(float[] named, String label, String by, int rotate) {
        if (marks.size() >= MAX) {
            throw new IllegalArgumentException("this camera holds at most " + MAX
                    + " marks and already has that many. Remove one with unmark=ID, or all "
                    + "of them with unmark=all.");
        }
        boolean box = named.length == 4;
        float[] centre = Geom.toSensor(named[0], named[1], rotate);
        float w = box ? named[2] : 0f;
        float h = box ? named[3] : 0f;
        if (box && (rotate == 90 || rotate == 270)) {
            float t = w;
            w = h;
            h = t;
        }
        Mark m = new Mark(nextId++, box, centre[0], centre[1], w, h,
                Parse.label(label), Parse.label(by));
        marks.add(m);
        return m;
    }

    public synchronized boolean remove(int id) {
        for (int i = 0; i < marks.size(); i++) {
            if (marks.get(i).id == id) {
                marks.remove(i);
                return true;
            }
        }
        return false;
    }

    public synchronized int removeAll() {
        int n = marks.size();
        marks.clear();
        return n;
    }

    public synchronized int count() {
        return marks.size();
    }

    /**
     * Every mark, in the coordinates of the picture these settings produce.
     *
     * in_crop says whether the mark is inside what the camera is showing right now, which
     * is what an agent needs before it says "the one I just made" and what the panel counts
     * to tell somebody a mark is off the view. The overlap is tested on a square nominal
     * frame: scaling each axis by its real pixel count moves both rectangles the same way,
     * so it cannot change the answer.
     */
    public synchronized JSONObject toJson(CamSettings s) throws JSONException {
        final int n = 10000;
        int[] roi = Geom.roi(n, n, s.zoom, s.cx, s.cy, s.rotate);
        JSONArray arr = new JSONArray();
        for (Mark m : marks) {
            float[] seen = Geom.toSeen(m.cx, m.cy, s.rotate);
            float w = m.w;
            float h = m.h;
            if (m.box && (s.rotate == 90 || s.rotate == 270)) {
                float t = w;
                w = h;
                h = t;
            }
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("kind", m.box ? "box" : "point");
            o.put("cx", CamSettings.round3(seen[0]));
            o.put("cy", CamSettings.round3(seen[1]));
            if (m.box) {
                o.put("w", CamSettings.round3(w));
                o.put("h", CamSettings.round3(h));
            }
            o.put("label", m.label);
            o.put("by", m.by);
            o.put("at", m.at);
            int[] rect = m.box
                    ? new int[]{Math.round((m.cx - m.w / 2) * n), Math.round((m.cy - m.h / 2) * n),
                                Math.max(1, Math.round(m.w * n)), Math.max(1, Math.round(m.h * n))}
                    : new int[]{Math.round(m.cx * n), Math.round(m.cy * n), 1, 1};
            o.put("in_crop", Geom.overlap(rect, roi));
            arr.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("marks", arr);
        out.put("count", marks.size());
        out.put("max", MAX);
        out.put("rotate", s.rotate);
        out.put("coordinates", "cx, cy, w and h name a place in the picture you SEE, the "
                + "same coordinates cx, cy and focusbox use. A mark is kept where it sits on "
                + "the sensor and mapped back through rotate every time it is read, so "
                + "remounting the phone does not move a mark off the part it names.");
        return out;
    }
}
