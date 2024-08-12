cmake_minimum_required(VERSION 3.4.1)

# Build our own native library
add_library (
        sound
        SHARED
        sound.cpp
        bridge.cpp
        OneShotSampleSource.cpp
        SampleBuffer.cpp
        SampleSource.cpp
        SimpleMultiPlayer.cpp
        stream/FileInputStream.cpp
        stream/InputStream.cpp
        stream/MemInputStream.cpp
        wav/AudioEncoding.cpp
        wav/WavChunkHeader.cpp
        wav/WavFmtChunkHeader.cpp
        wav/WavRIFFChunkHeader.cpp
        wav/WavStreamReader.cpp
)

# Find the Oboe package
find_package (oboe REQUIRED CONFIG)

# Specify the libraries which our native library is dependent on, including Oboe
target_link_libraries(
        sound
        log
        oboe::oboe
)