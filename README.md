# WebDAV Streamer

## Introduction
WebDAV Streamer is a desktop application designed to stream media content from WebDAV servers. Inspired by the Infuse app on iOS, this project aims to provide a similar experience for desktop users. The application is built using JavaFX.

## Features
- Stream video and audio content from WebDAV servers
- Intuitive user interface built with JavaFX
- Support for various media formats
- Easy navigation and media library management
- Cross-platform compatibility (Windows, macOS, Linux)

## Getting Started

### Installation
The executable file can be found in the [releases](https://github.com/mitul72/StreamDav/releases) section of the GitHub repository.

## Building the project
### Prerequisites
- Java JDK 25 or later (the Gradle wrapper downloads Gradle itself)
### Build instructions
1. Clone the repository:
```
git clone https://github.com/mitul72/StreamDav.git
```
2. Navigate to the project directory:
```
cd StreamDav
```
3. Build the project with Gradle:
```
./gradlew build
```
4. Run the application using Gradle:
```
./gradlew run
```


## Usage
1. Launch the WebDAV Streamer application.
2. Enter the URL and credentials for your WebDAV server.
3. Browse and select the media content you wish to stream.
4. Enjoy your media content streamed directly from your WebDAV server.

## Playback
StreamDav plays media in the app with [mpv](https://mpv.io) when libmpv is installed: MKV, HEVC, AV1, multiple audio tracks and subtitles all work.

- Linux: install mpv with your package manager (e.g. `pacman -S mpv` or `apt install libmpv2`).
- macOS: `brew install mpv`.
- Windows: put `libmpv-2.dll` on your `PATH`.

To use a libmpv in another location, start the app with `-Dstreamdav.libmpv=/path/to/libmpv`.

Without libmpv, MP4, M4V, MP3 and WAV files play in the JavaFX player, and other formats open in an external player such as mpv or VLC.

## Contributing
Contributions are welcome! If you would like to contribute, please follow these steps:
1. Fork the repository.
2. Create a new branch for your feature (`git checkout -b feature/your-feature`).
3. Commit your changes (`git commit -am 'Add some feature'`).
4. Push to the branch (`git push origin feature/your-feature`).
5. Create a new pull request.

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
