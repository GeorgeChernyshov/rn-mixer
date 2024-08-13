//
// Created by 37443 on 13/08/2024.
//

#ifndef POLANDANDROIDARMS_M4ATYPES_H
#define POLANDANDROIDARMS_M4ATYPES_H

namespace parselib {

/*
 * Declarations for various (cross-platform) WAV-specific data types.
 */
    typedef unsigned int M4aRiffID;    // A "four character code" (i.e. FOURCC)
    typedef int M4aRiffInt32;          // A 32-bit signed integer
    typedef short M4aRiffInt16;        // A 16-bit signed integer

/*
 * Packs the specified characters into a 32-bit value in accordance with the Microsoft
 * FOURCC specification.
 */
    inline M4aRiffID makeM4aRiffID(char a, char b, char c, char d) {
        return ((M4aRiffID)d << 24) | ((M4aRiffID)c << 16) | ((M4aRiffID)b << 8) | (M4aRiffID)a;
    }

}

#endif //POLANDANDROIDARMS_M4ATYPES_H
