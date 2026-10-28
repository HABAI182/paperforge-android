cmake_minimum_required(VERSION 3.22.1)
project(paperforge_opencv LANGUAGES C CXX)

# Build only APIs actually used by scanning, enhancement, and Paddle preprocessing.
# photo is required for inpainting; imgcodecs retains JPEG, PNG, and WebP support.
set(BUILD_LIST core,imgproc,imgcodecs,photo,java CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_FAT_JAVA_LIB ON CACHE BOOL "" FORCE)
set(BUILD_JAVA ON CACHE BOOL "" FORCE)
set(ANDROID_PROJECTS_BUILD_TYPE GRADLE CACHE STRING "" FORCE)
# Upstream generates an unused SDK skeleton during configure. Confine its cleanup to this build.
set(ANDROID_BUILD_BASE_DIR "${CMAKE_BINARY_DIR}/unused-android-sdk" CACHE PATH "" FORCE)
foreach(option BUILD_ANDROID_PROJECTS BUILD_ANDROID_EXAMPLES BUILD_TESTS BUILD_PERF_TESTS
        BUILD_EXAMPLES BUILD_DOCS BUILD_APPS BUILD_opencv_python2 BUILD_opencv_python3
        BUILD_KOTLIN_EXTENSIONS WITH_OPENCL WITH_IPP WITH_ITT WITH_TBB WITH_PROTOBUF
        WITH_OPENEXR WITH_TIFF WITH_OPENJPEG WITH_FFMPEG WITH_GSTREAMER WITH_KLEIDICV WITH_ADE)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(CPU_DISPATCH "" CACHE STRING "" FORCE)

if(PAPERFORGE_OPENCV_SOURCE)
    # Optional local source checkout, e.g. the verified archive extracted for offline builds.
    add_subdirectory("${PAPERFORGE_OPENCV_SOURCE}" upstream)
else()
    include(FetchContent)
    FetchContent_Declare(opencv_upstream
        URL https://codeload.github.com/opencv/opencv/zip/cbee6841638edb6fbc8110df7cd52bb8e3d66211
        URL_HASH SHA256=a62159403e81cef2e924a0cf9feb35244237e88b46607a4eb59f71df64ad665a
    )
    FetchContent_MakeAvailable(opencv_upstream)
endif()
target_link_options(opencv_java PRIVATE "-Wl,-z,max-page-size=16384")
