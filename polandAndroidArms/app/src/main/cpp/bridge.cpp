#include <jni.h>
#include <android/log.h>

#include "sound.h"

static SimpleNoiseMaker sPlayer;

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_polandandroidarms_MainActivity_testFunction(JNIEnv *env,jobject obj) {
    __android_log_print(ANDROID_LOG_INFO, "test", "Test function successfully called.");
    return 17;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_playAudio(JNIEnv *env,jobject obj) {
    oboe::Result result = sPlayer.open();
    if (result == oboe::Result::OK) {
        result = sPlayer.start();
    }
}