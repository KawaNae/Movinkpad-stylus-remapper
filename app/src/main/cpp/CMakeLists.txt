cmake_minimum_required(VERSION 3.22.1)
project(pengrab)

# Minimal JNI library wrapping the EVIOCGRAB ioctl. Only depends on libc (ioctl).
add_library(pengrab SHARED grabjni.c)
