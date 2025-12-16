cmake_minimum_required(VERSION 3.22.1)

project("yuv_encoder")

set(CMAKE_CXX_STANDARD 17)

add_library(
        yuv_encoder
        SHARED
        yuv_encoder.cpp)
