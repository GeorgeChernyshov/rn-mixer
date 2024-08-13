//
// Created by 37443 on 13/08/2024.
//

#include "FileSource.h"

namespace iolib {

    void FileSource::mixAudio(float* outBuff, int numChannels, int32_t numFrames) {
        int32_t numSamples = mSampleBuffer->getNumSamples();
        int32_t sampleChannels = mSampleBuffer->getProperties().channelCount;
        int32_t samplesLeft = numSamples - mCurSampleIndex;
        int32_t numWriteFrames = mIsPlaying
                                 ? std::min(numFrames, samplesLeft / sampleChannels)
                                 : 0;

        if (numWriteFrames != 0) {
            const float* data  = mSampleBuffer->getSampleData();
            if ((sampleChannels == 1) && (numChannels == 1)) {
                // MONO output from MONO samples
                for (int32_t frameIndex = 0; frameIndex < numWriteFrames; frameIndex++) {
                    outBuff[frameIndex] += data[mCurSampleIndex++];
                }
            } else if ((sampleChannels == 1) && (numChannels == 2)) {
                // STEREO output from MONO samples
                int dstSampleIndex = 0;
                for (int32_t frameIndex = 0; frameIndex < numWriteFrames; frameIndex++) {
                    outBuff[dstSampleIndex++] += data[mCurSampleIndex];
                    outBuff[dstSampleIndex++] += data[mCurSampleIndex++];
                }
            } else if ((sampleChannels == 2) && (numChannels == 1)) {
                // MONO output from STEREO samples
                int dstSampleIndex = 0;
                for (int32_t frameIndex = 0; frameIndex < numWriteFrames; frameIndex++) {
                    outBuff[dstSampleIndex++] += data[mCurSampleIndex++] +
                                                 data[mCurSampleIndex++];
                }
            } else if ((sampleChannels == 2) && (numChannels == 2)) {
                // STEREO output from STEREO samples
                int dstSampleIndex = 0;
                for (int32_t frameIndex = 0; frameIndex < numWriteFrames; frameIndex++) {
                    outBuff[dstSampleIndex++] += data[mCurSampleIndex++];
                    outBuff[dstSampleIndex++] += data[mCurSampleIndex++];
                }
            }

            if (mCurSampleIndex >= numSamples) {
                mIsPlaying = false;
            }
        }

        // silence
        // no need as the output buffer would need to have been filled with silence
        // to be mixed into
    }

}