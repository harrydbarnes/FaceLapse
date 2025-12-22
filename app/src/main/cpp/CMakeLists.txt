cmake_minimum_required(VERSION 3.22.1)

project("yuv_encoder")

set(CMAKE_CXX_STANDARD 17)

find_library( # Sets the name of the path variable.
              jnigraphics-lib
              # Specifies the name of the NDK library that
              # you want CMake to locate.
              jnigraphics )

add_library(
        yuv_encoder
        SHARED
        yuv_encoder.cpp)

target_link_libraries( # Specifies the target library.
                       yuv_encoder

                       # Links the target library to the log library
                       # included in the NDK.
                       ${jnigraphics-lib} )
