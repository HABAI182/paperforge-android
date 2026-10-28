cmake_minimum_required(VERSION 3.22.1)
project(paperforge_jpeg2000 LANGUAGES C CXX)

include(FetchContent)
FetchContent_Declare(jp2_upstream
    URL https://codeload.github.com/ThalesGroup/JP2ForAndroid/zip/8ad43abb5d8e2caf2147162d3bfe5569581b5d1f
    URL_HASH SHA256=b7b0e490aebcd10d0f9522af225f387a185426b802ffc7a6032d56624eb01699
)
FetchContent_MakeAvailable(jp2_upstream)
add_subdirectory("${jp2_upstream_SOURCE_DIR}/library" upstream-library)
