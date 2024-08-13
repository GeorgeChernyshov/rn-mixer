//
// Created by 37443 on 13/08/2024.
//

#ifndef POLANDANDROIDARMS_M4AFMTCHUNKHEADER_H
#define POLANDANDROIDARMS_M4AFMTCHUNKHEADER_H

#include "M4aChunkHeader.h"

class InputStream;

namespace parselib {

/**
 * Encapsulates a WAV file 'fmt ' chunk.
 */
    class M4aFmtChunkHeader : public M4aChunkHeader {
    public:
        static const M4aRiffID RIFFID_FMT;

        // Microsoft Encoding IDs
        static const short ENCODING_PCM = 1;
        static const short ENCODING_ADPCM = 2; // Microsoft ADPCM Format
        static const short ENCODING_IEEE_FLOAT = 3; // samples from -1.0 -> 1.0

        M4aRiffInt16 mEncodingId;  /** Microsoft WAV encoding ID (see above) */
        M4aRiffInt16 mNumChannels;
        M4aRiffInt32 mSampleRate;
        M4aRiffInt32 mAveBytesPerSecond;
        M4aRiffInt16 mBlockAlign;
        M4aRiffInt16 mSampleSize;
        M4aRiffInt16 mExtraBytes;

        M4aFmtChunkHeader();

        M4aFmtChunkHeader(M4aRiffID tag);

        void normalize();

        void read(InputStream *stream);
    };

}

#endif //POLANDANDROIDARMS_M4AFMTCHUNKHEADER_H
