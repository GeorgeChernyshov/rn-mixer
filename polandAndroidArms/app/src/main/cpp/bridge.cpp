#include <jni.h>
#include <android/log.h>

#include "sound.h"
#include "SimpleMultiPlayer.h"
#include "stream/MemInputStream.h"
#include "wav/WavStreamReader.h"
#include "SampleBuffer.h"
#include "SampleSource.h"

#include "vector"

static iolib::SimpleMultiPlayer sPlayer;
static std::vector<iolib::SampleBuffer*> buffers;
static std::vector<iolib::OneShotSampleSource*> sources;

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_polandandroidarms_MainActivity_testFunction(JNIEnv *env,jobject obj) {
    __android_log_print(ANDROID_LOG_INFO, "test", "Test function successfully called.");
    return 17;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_preparePlayer(JNIEnv *env, jobject thiz) {
    sPlayer.setupAudioStream(2);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_loadTrack(JNIEnv *env, jobject thiz,
                                                          jbyteArray data) {
    jboolean isCopy;
    auto stream = parselib::MemInputStream(
            reinterpret_cast<unsigned char *>(
                    env->GetByteArrayElements(data, &isCopy)
                    ),
            env->GetArrayLength(data)
            );

    auto reader = parselib::WavStreamReader(&stream);
    reader.parse();
    auto buffer = new iolib::SampleBuffer();
    buffer->loadSampleData(&reader);
    buffers.push_back(buffer);
    auto source = new iolib::OneShotSampleSource(buffer, 1);
    sources.push_back(source);
    sPlayer.addSampleSource(source, buffer);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_playAudio(JNIEnv *env,jobject obj) {
//    oboe::Result result = sPlayer.open();
//    if (result == oboe::Result::OK) {
//        result = sPlayer.start();
//    }
    sPlayer.startStream();
    for (int i = 0; i < sources.size(); ++i) {
        sPlayer.triggerDown(i);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_pauseAudio(JNIEnv *env, jobject thiz) {
    sPlayer.pause();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_resumeAudio(JNIEnv *env, jobject thiz) {
    sPlayer.resume();
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_example_polandandroidarms_MainActivity_getCurrentPosition(JNIEnv *env, jobject thiz) {
    return sources[0]->getPosition();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_polandandroidarms_MainActivity_setPosition(JNIEnv *env, jobject thiz,
                                                            jfloat position) {
    sPlayer.pause();
    for (auto &source : sources) {
        source->setPosition(position);
    }

    sPlayer.resume();
}