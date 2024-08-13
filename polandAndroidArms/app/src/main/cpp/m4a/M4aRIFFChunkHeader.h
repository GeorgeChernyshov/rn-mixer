//
// Created by 37443 on 13/08/2024.
//

#ifndef POLANDANDROIDARMS_M4ARIFFCHUNKHEADER_H
#define POLANDANDROIDARMS_M4ARIFFCHUNKHEADER_H

#include "M4aChunkHeader.h"

namespace parselib {

    class InputStream;

    class M4aRIFFChunkHeader : public M4aChunkHeader {
    public:
        static const M4aRiffID RIFFID_RIFF;

        static const M4aRiffID RIFFID_WAVE;

        M4aRiffID mFormatId;

        M4aRIFFChunkHeader();

        M4aRIFFChunkHeader(M4aRiffID tag);

        virtual void read(InputStream *stream);
    };

}

#endif //POLANDANDROIDARMS_M4ARIFFCHUNKHEADER_H
