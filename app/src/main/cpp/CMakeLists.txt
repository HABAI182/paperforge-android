cmake_minimum_required(VERSION 3.22.1)
project(paperforge_scan LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_library(paperforge_scan SHARED paper_pixels.cpp)
set_source_files_properties(paper_pixels.cpp PROPERTIES COMPILE_OPTIONS "-O3")
target_link_libraries(paperforge_scan PRIVATE log android)
target_link_options(paperforge_scan PRIVATE "-Wl,-z,max-page-size=16384")
