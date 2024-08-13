//
// Created by 37443 on 13/08/2024.
//

#ifndef POLANDANDROIDARMS_M4ACHUNKHEADER_H
#define POLANDANDROIDARMS_M4ACHUNKHEADER_H

#include "M4aTypes.h"

namespace parselib {

    class InputStream;

/**
 * Superclass for all RIFF chunks. Handles the chunk ID and chunk size.
 * Concrete subclasses include chunks for 'RIFF' and 'fmt ' chunks.
 */
    class M4aChunkHeader {
    public:
        static const M4aRiffID RIFFID_DATA;

        M4aRiffID mChunkId;
        M4aRiffInt32 mChunkSize;

        M4aChunkHeader() : mChunkId(0), mChunkSize(0) {}

        M4aChunkHeader(M4aRiffID chunkId) : mChunkId(chunkId), mChunkSize(0) {}

        /**
         * Reads the contents of the chunk. In this class, just the ID and size fields.
         * When implemented in a concrete subclass, that implementation MUST call this (super) method
         * as the first step. It may then read the fields specific to that chunk type.
         */
        virtual void read(InputStream *stream);
    };

}

#endif //POLANDANDROIDARMS_M4ACHUNKHEADER_H
