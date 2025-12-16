cmake_minimum_required(VERSION 3.22.1)

project("yuv_encoder")

add_library(
        yuv_encoder
        SHARED
        yuv_encoder.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        yuv_encoder
        ${log-lib})
