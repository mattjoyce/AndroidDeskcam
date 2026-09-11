package main

import "fmt"

func usage() {
	fmt.Print(`deskcam - control the bench camera over HTTP

  deskcam snap [-o FILE] [k=v ...]     full-resolution still, cropped to the ROI
  deskcam frame [-o FILE] [k=v ...]    fast preview-resolution frame
  deskcam raw [-o FILE] [k=v ...]      full-sensor RAW as a DNG, for measurement work
  deskcam burst N [-o DIR] [k=v ...]   N frames with identical settings, into a directory
                                       add format=raw for DNG frames
  deskcam focussweep [-o DIR] [from=D to=D steps=N]
                                       walk the lens and keep a still at each step, for
                                       focus stacking. The steps are equal in dioptres,
                                       which is equal in depth of field. To find one
                                       sharpest position instead, see deskcam focus hunt
  deskcam walk vary=NAME values=A,B,C  one still at each value, e.g. vary=torch
                                       values=0,10,20,45. You supply the values; the two
                                       axes with a step rule of their own are below
  deskcam bracket [-o DIR] [base=1/240 stops=4]
                                       stills at doubling exposures, for merging a lit
                                       panel against a dark bezel. Set the base to one
                                       period of the panel's PWM
  deskcam stream [-o FILE] [n=N]       MJPEG stream (default 30 frames to a file).
                                       A stream is a view: it takes fps, n, w, h and
                                       jpegq, and refuses anything that would change
                                       the camera. Use deskcam set for those.

  deskcam script run FILE [-o DIR]     run a tape of verbs as one operation. Holds the
                                       camera for its duration, so nothing can change it
                                       between two steps. One line per step, each capture
                                       written as it arrives. Non-zero if it did not
                                       finish; deskcam api lists the verbs

  deskcam status                       full JSON state
  deskcam show                         one-line summary
  deskcam show sharpness=1             the same, with a fresh sharpness reading. Move the
                                       focus, read the number, repeat: the peak is focus
  deskcam set k=v [k=v ...]            apply any control parameters
  deskcam reset                        restore defaults
  deskcam recall FILE.json             restore the settings of a past capture

  deskcam aatest [k=v ...]             two captures, same settings. Prints the smallest
                                       difference a measurement can honestly claim, and
                                       records it. analyse linearity refuses steps inside it.
  deskcam scale FILE [--pitch-mm N]    px/mm from a rule or graph paper in the frame, and
                                       records it, so later captures with the same framing
                                       carry it in their sidecars
  deskcam measure FILE X1,Y1 X2,Y2     millimetres between two points of a capture, using
                                       the scale in its sidecar
  deskcam analyse scale FILE           the measurement without recording it
  deskcam analyse linearity DIR        pixel value against exposure
  deskcam analyse burst-noise DIR      how far averaging a burst lowers the noise
  deskcam analyse average DIR          average a burst into one 16-bit image
  deskcam analyse stack DIR            one image sharp at every depth, from a focus sweep
  deskcam analyse hdr DIR              one linear image from a bracket, on the real exposures

  deskcam zoom N                       set zoom (1.0 = full sensor)
  deskcam pan up|down|left|right [amt] nudge the view (default 0.25)
  deskcam center                       recentre
  deskcam af                           one autofocus sweep
  deskcam focus METRES|auto            manual focus distance
  deskcam focus hunt [from=D to=D]     walk the lens on the phone, print the curve, and
                                       leave it at the sharpest position. Fix the exposure
                                       first, or the hunt climbs the exposure loop. Exits
                                       non-zero, and puts the focus back, when the curve
                                       has no peak in the range
  deskcam exposure VALUE               1/120, 8ms, 250us, 0.5s
  deskcam iso N                        manual sensitivity
  deskcam auto                         back to auto exposure and focus
  deskcam torch 0-45|off|max           rear LED brightness

  deskcam cameras                      list cameras and capabilities
  deskcam api                          machine-readable API description
  deskcam open                         open the web control panel
  deskcam serve [PORT] [--apk FILE]    local console on port 9000 with two codes for
                                       the phone: one installs the app, one pairs it.
                                       It hands out this clone's build if there is one,
                                       otherwise the latest release; --apk release
                                       always points at the release
  deskcam token new|show|clear         make, show or remove the access key

  deskcam use URL                      remember a target, e.g. http://192.168.86.120:8080
  deskcam usb [PORT]                   tunnel over USB via adb and use that
  deskcam wifi                         switch back to the device's Wi-Fi address
  deskcam start | stop                 start or stop the service on the phone (needs adb)
  deskcam which                        print the current target
  deskcam version                      print this CLI's version

Any command also accepts k=v words, applied before the image is taken:
  deskcam snap zoom=6 cx=0.3 cy=0.7 torch=25 exposure=1/120

Camera state persists until you change it (zoom, cx, cy, focus, exposure, iso, torch,
awb, measure, rotate). Presentation applies to one request and is then forgotten
(w, h, jpegq). deskcam api prints the whole list.

Environment: DESKCAM_URL, DESKCAM_TOKEN, DESKCAM_TIMEOUT, DESKCAM_SHOTS, DESKCAM_SERIAL
`)
}
