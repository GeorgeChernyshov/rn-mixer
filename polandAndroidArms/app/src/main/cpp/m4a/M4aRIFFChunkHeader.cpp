/*
 * Copyright 2019 The Android Open Source Project
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
#include "M4aRIFFChunkHeader.h"
#include "../stream/InputStream.h"

namespace parselib {

const M4aRiffID M4aRIFFChunkHeader::RIFFID_RIFF = makeM4aRiffID('R', 'I', 'F', 'F');
const M4aRiffID M4aRIFFChunkHeader::RIFFID_WAVE = makeM4aRiffID('W', 'A', 'V', 'E');

M4aRIFFChunkHeader::M4aRIFFChunkHeader() : M4aChunkHeader(RIFFID_RIFF) {
    mFormatId = RIFFID_WAVE;
}

M4aRIFFChunkHeader::M4aRIFFChunkHeader(M4aRiffID tag) : M4aChunkHeader(tag) {
    mFormatId = RIFFID_WAVE;
}

void M4aRIFFChunkHeader::read(InputStream *stream) {
    M4aChunkHeader::read(stream);
    stream->read(&mFormatId, sizeof(mFormatId));
}

} // namespace parselib
