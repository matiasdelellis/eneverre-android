# Eneverre Android

This is an Android client for [neverre-api](https://github.com/matiasdelellis/eneverre-api). 😄

This project is intended solely to display streams from different manufacturers in a single application.

### Features 🎉
* Manufacturer independence: See [eneverre-api](https://github.com/matiasdelellis/eneverre-api) for details.
* PTZ support: Maybe, for now only from [Thingino](https://thingino.com/) cameras using [thingino-control](https://github.com/matiasdelellis/thingino-control).
* Privacy Mode: A bit of a liar... if you have a Thingino camera with PTZ, it moves to a private position, and this app hides the image, but the camera still transmits Video and Audio.

### Context, explanation or catharsis 🙈😅
1. My couple's friends get scared because there's a camera in the dining room, so they hide, unplug, or break my camera. 😞
2. I bought a Wyze Cam Pan V3 clone camera that can be hidden in a nice way to show that it is not recording. 😬
3. I want to use it together with my other Tp Link Tapo cameras. Impossible!. 😞
4. I found [LightNVR](https://github.com/opensensor/lightNVR) and love that. 😄
5. I created the [LightNVR android](https://github.com/matiasdelellis/lightNVR-viewer-android) project, to see all the cameras. 😄
6. Great, now I have to control the movements of this camera and activate privacy mode.
   Well.. I'm searching the internet for a simple project to control these cameras via Onvif. Impossible!. 😞
7. I found the Thingnino project and fell in love again!. ❤️
   I installed it on this camera and it works great. 😄
   ...but has no concept of privacy.. Maybe [one](https://github.com/themactep/thingino-firmware/issues/290) day..
8. Ok, It seems to support onvif, maybe I can simulate it.
   Impossible, I prefer to read the 795 pages of ISAPI documentation for Hikvision, which is more understandable. 😞
9. Let's see how to move the camera from the administration panel? 😬
   Ohhhh.. A GET call with Digest authentication. I can do that!. 😄
   It's as simple as this: `curl -s http://USERNAME:PASSWORD@THINGINO_IP/x/json-motor.cgi?d=g&x=DX&y=DY` 😙
   Well, do we really need Onvif? That's how thingino-control was born with 34 lines of bash. 😅
10. In the meantime, I abandoned LightNVR and migrated to [MediaMTX](https://github.com/bluenviron/mediamtx) to exposing the cameras to the Internet and making recordings.
11. Does it make sense to keep the other client I wrote? Well, lightNVR isn't going to control the motors, so we built a simple API that puts everything together.
    This is how eneverre-api was born with 95 lines of code in Python. 😅
12. Finally, Eneverre Android was born.. 🎉

Well, Much to do, but it does the minimum I need.
* A simple Android app where my couple and I can see all the cameras.
* Being able to control my Thingino cameras.
* Let the camera hide itself in a friendly way when my couple's friends come over.

I hope you like it... 😃

### Screenshots 😍

Cameras View | Fixed Camera | PTZ Camera | Private Camera
-- | -- | -- | -- 
![](https://github.com/user-attachments/assets/36dae995-21a4-4d9e-8c3e-16aed99bdfda) | ![](https://github.com/user-attachments/assets/9d2dd21a-28e9-4e1f-80ed-5b723fae58b8) | ![](https://github.com/user-attachments/assets/7d6b5c89-ae4e-4424-8465-8b82ecfa87c1) | ![](https://github.com/user-attachments/assets/d6210614-85c7-459d-985a-bf01fa13d6e8)
