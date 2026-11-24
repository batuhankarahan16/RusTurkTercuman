#!/usr/bin/env bash
# GitHub'ın derleme sırasında çalıştırdığı hazırlık betiği:
# dosyaları Android proje düzenine yerleştirir ve ses modellerini indirir.
set -euo pipefail

mkdir -p app/src/main/java/com/ceviri/rustr app/src/main/res/layout app/src/main/res/values \
         app/src/main/assets/asr app/src/main/assets/giga app/libs
mv app.gradle.kts app/build.gradle.kts
mv imza.jks app/
mv AndroidManifest.xml app/src/main/
mv *.kt app/src/main/java/com/ceviri/rustr/
mv activity_main.xml app/src/main/res/layout/
mv strings.xml themes.xml app/src/main/res/values/

B=https://github.com/k2-fsa/sherpa-onnx/releases/download

# sherpa-onnx Android kütüphanesi
curl -fsSL -o app/libs/sherpa-onnx.aar $B/v2.0.3/sherpa-onnx-2.0.3.aar

# Hızlı akışlı Rusça model (canlı altyazı için)
M=sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16
curl -fsSL $B/asr-models/$M.tar.bz2 | tar xj
cp $M/encoder.int8.onnx $M/decoder.onnx $M/joiner.int8.onnx $M/tokens.txt app/src/main/assets/asr/
rm -rf $M

# Büyük ve doğru Rusça model (GigaAM v2) - sesli okunan cümle için
G=sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19
curl -fsSL $B/asr-models/$G.tar.bz2 | tar xj
cp $G/model.int8.onnx $G/tokens.txt app/src/main/assets/giga/
rm -rf $G

# Ses izi (konuşmacı tanıma) modeli
curl -fsSL -o app/src/main/assets/spk.onnx $B/speaker-recongition-models/nemo_en_titanet_small.onnx

ls -la app/libs app/src/main/assets app/src/main/assets/asr app/src/main/assets/giga
