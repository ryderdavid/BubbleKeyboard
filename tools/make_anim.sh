#!/bin/sh
# Turns the frames from RenderAnimationTest into an MP4 and a GIF.
# Run after: ./gradlew testDebugUnitTest -Prender --tests '*RenderAnimationTest'
set -e
cd "$(dirname "$0")/../app/build/previews/anim"
ffmpeg -loglevel error -y -framerate 30 -i frame_%04d.png -vf "scale=540:-2:flags=lanczos,format=yuv420p" -c:v libx264 -crf 20 -movflags +faststart ../bubble-keyboard.mp4
ffmpeg -loglevel error -y -framerate 30 -i frame_%04d.png -vf "fps=30,scale=420:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" ../bubble-keyboard.gif
echo "wrote app/build/previews/bubble-keyboard.mp4 and .gif"
