/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "SampleSource.h"

namespace iolib {

SampleSource::SampleSource(SampleBuffer *sampleBuffer, float pan)
    : mSampleBuffer(sampleBuffer), mCurSampleIndex(0), mIsPlaying(false), mGain(1.0f)
{
    setPan(pan);

    // Calculating maximum average amplitude for the track
    auto data = mSampleBuffer->getSampleData();
    auto total = mSampleBuffer->getNumSamples();
    float maxAmplitude = 0;

    // We'll take average for a chunk since that is a good practical approximation
    int chunkSize = 2048;
    for (int i = 0; i < total; i+=chunkSize) {
        // Skipping last chunk to avoid dealing with end-of-data bounds
        if ((total - i) < chunkSize)
            continue;

        // Calculate average amplitude for the chunk
        float averageAmplitude = 0;
        for (int j = 0; j < chunkSize; j++) {
            float f = data[i + j];
            if (f < 0) f *= -1;
            averageAmplitude += f;
        }

        averageAmplitude /= (float)chunkSize;

        // Update the max amp if necessary
        if (averageAmplitude > maxAmplitude)
            maxAmplitude = averageAmplitude;
    }

    mMaxAmplitude = maxAmplitude;
}

}
