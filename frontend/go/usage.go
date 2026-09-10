package main

import "fmt"

func usage() {
	fmt.Print(`deskcam - control the bench camera over HTTP

  deskcam snap [-o FILE] [k=v ...]     full-resolution still, cropped to the ROI
  deskcam frame [-o FILE] [k=v ...]    fast preview-resolution frame
  deskcam raw [-o FILE] [k=v ...]      full-sensor RAW as a DNG, for measurement work
  deskcam burst N [-o DIR] [k=v ...]   N frames with identical settings, into a directory
                                       add format=raw for DNG frames
  deskcam stream [-o FILE] [n=N]       MJPEG stream (default 30 frames to a file).
                                       A stream is a view: it takes fps, n, w, h and
                                       jpegq, and refuses anything that would change
                                       the camera. Use deskcam set for those.

  deskcam status                       full JSON state
  deskcam show                         one-line summary
  deskcam show sharpness=1             the same, with a fresh sharpness reading. Move the
                                       focus, read the number, repeat: the peak is focus
  deskcam set k=v [k=v ...]            apply any control parameters
  deskcam reset                        restore defaults
  deskcam recall FILE.json             restore the settings of a past capture

  deskcam aatest [k=v ...]             two captures, same settings. Prints the smallest
                                       difference a measurement can honestly claim, and
                                       records it for the analysis tools to enforce.
  deskcam scale FILE [--pitch-mm N]    px/mm from a rule or graph paper in the frame, and
                                       records it, so later captures with the same framing
                                       carry it in their sidecars
  deskcam measure FILE X1,Y1 X2,Y2     millimetres between two points of a capture, using
                                       the scale in its sidecar
  deskcam analyse scale FILE           the measurement without recording it
  deskcam analyse linearity DIR        pixel value against exposure
  deskcam analyse burst-noise DIR      how far averaging a burst lowers the noise

  deskcam zoom N                       set zoom (1.0 = full sensor)
  deskcam pan up|down|left|right [amt] nudge the view (default 0.25)
  deskcam center                       recentre
  deskcam af                           one autofocus sweep
  deskcam focus METRES|auto            manual focus distance
  deskcam exposure VALUE               1/120, 8ms, 250us, 0.5s
  deskcam iso N                        manual sensitivity
  deskcam auto                         back to auto exposure and focus
  deskcam torch 0-45|off|max           rear LED brightness

  deskcam cameras                      list cameras and capabilities
  deskcam api                          machine-readable API description
  deskcam open                         open the web control panel
  deskcam serve [PORT]                 local console with QR pairing (default 9000)
  deskcam token new|show|clear         make, show or remove the access key

  deskcam use URL                      remember a target, e.g. http://192.168.86.120:8080
  deskcam usb [PORT]                   tunnel over USB via adb and use that
  deskcam wifi                         switch back to the device's Wi-Fi address
  deskcam start | stop                 start or stop the service on the phone (needs adb)
  deskcam which                        print the current target

Any command also accepts k=v words, applied before the image is taken:
  deskcam snap zoom=6 cx=0.3 cy=0.7 torch=25 exposure=1/120

Camera state persists until you change it (zoom, cx, cy, focus, exposure, iso, torch,
awb, measure, rotate). Presentation applies to one request and is then forgotten
(w, h, jpegq). deskcam api prints the whole list.

Environment: DESKCAM_URL, DESKCAM_TOKEN, DESKCAM_TIMEOUT, DESKCAM_SHOTS, DESKCAM_SERIAL
`)
}
