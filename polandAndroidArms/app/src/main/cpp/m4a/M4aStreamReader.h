//
// Created by 37443 on 13/08/2024.
//

#ifndef POLANDANDROIDARMS_M4ASTREAMREADER_H
#define POLANDANDROIDARMS_M4ASTREAMREADER_H

#include <map>

#include "AudioEncoding.h"
#include "M4aRIFFChunkHeader.h"
#include "M4aFmtChunkHeader.h"

namespace parselib {

    class InputStream;

    class M4aStreamReader {
    public:
        M4aStreamReader(InputStream *stream);

        int getSampleRate() { return mFmtChunk->mSampleRate; }

        int getNumSampleFrames() {
            return mDataChunk->mChunkSize / (mFmtChunk->mSampleSize / 8) / mFmtChunk->mNumChannels;
        }

        int getNumChannels() { return mFmtChunk != 0 ? mFmtChunk->mNumChannels : 0; }

        int getSampleEncoding();

        int getBitsPerSample() { return mFmtChunk->mSampleSize; }

        void parse();

        // Data access
        void positionToAudio();

        static constexpr int ERR_INVALID_FORMAT    = -1;
        static constexpr int ERR_INVALID_STATE    = -2;

        int getDataFloat(float *buff, int numFrames);

        // int getData16(short *buff, int numFramees);

    protected:
        InputStream *mStream;

        std::shared_ptr<M4aRIFFChunkHeader> mWavChunk;
        std::shared_ptr<M4aFmtChunkHeader> mFmtChunk;
        std::shared_ptr<M4aChunkHeader> mDataChunk;

        long mAudioDataStartPos;

        std::map<M4aRiffID, std::shared_ptr<M4aChunkHeader>> mChunkMap;

    private:
        /*
         * Individual Format Readers/Converters
         */
        int getDataFloat_PCM8(float *buff, int numFrames);

        int getDataFloat_PCM16(float *buff, int numFrames);

        int getDataFloat_PCM24(float *buff, int numFrames);

        int getDataFloat_Float32(float *buff, int numFrames);
        int getDataFloat_PCM32(float *buff, int numFrames);
    };

}

#endif //POLANDANDROIDARMS_M4ASTREAMREADER_H
