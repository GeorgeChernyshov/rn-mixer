//
// Created by 37443 on 13/08/2024.
//

#ifndef POLANDANDROIDARMS_FILESOURCE_H
#define POLANDANDROIDARMS_FILESOURCE_H

#include <cstdint>

#include "DataSource.h"

#include "SampleBuffer.h"

namespace iolib {

/**
 * Defines an interface for audio data provided to a player object.
 * Concrete examples include OneShotSampleBuffer. One could imagine a LoopingSampleBuffer.
 * Supports stereo position via mPan member.
 */
    class FileSource: public DataSource {
    public:
        FileSource(SampleBuffer *sampleBuffer, float pan)
                : mSampleBuffer(sampleBuffer), mCurSampleIndex(0), mIsPlaying(false) {};

        virtual ~FileSource() {}

        virtual void mixAudio(float* outBuff, int numChannels, int32_t numFrames);

        void setPlayMode() { mCurSampleIndex = 0; mIsPlaying = true; }
        void setStopMode() { mIsPlaying = false; mCurSampleIndex = 0; }

        bool isPlaying() { return mIsPlaying; }

    protected:
        SampleBuffer    *mSampleBuffer;

        int32_t mCurSampleIndex;

        bool mIsPlaying;
    };

}

#endif //POLANDANDROIDARMS_FILESOURCE_H
