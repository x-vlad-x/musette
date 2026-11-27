# Musette 0.1.0

The first release of Musette, an unofficial YouTube Music player for Linux phones with a native
GTK4 and libadwaita interface for Phosh. It is built on SimpMusic 2.7.0 and made for the
FuriPhone FLX1s with FuriOS.

## Install

**With updates**, from one of the two signed repositories (ARM64):

```sh
# APT: deb.xvlad.com
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://deb.xvlad.com/xvlad.gpg | sudo tee /etc/apt/keyrings/xvlad.gpg >/dev/null
curl -fsSL https://deb.xvlad.com/xvlad.sources | sudo tee /etc/apt/sources.list.d/xvlad.sources >/dev/null
sudo apt update && sudo apt install musette

# or Flatpak: flatpak.xvlad.com
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak remote-add --user --if-not-exists xvlad https://flatpak.xvlad.com/xvlad.flatpakrepo
flatpak install --user xvlad io.github.x_vlad_x.Musette
```

Updates then come with `sudo apt upgrade` or `flatpak update`. The
[README](https://github.com/x-vlad-x/musette#install) has the key fingerprints and the details.

**The files** below need no repository, but don't update by themselves:

| Your system | File (ARM64) |
| --- | --- |
| FuriOS, Mobian and other Debian-based systems (Debian 13 or newer) | `musette_0.1.0-1_arm64.deb` |
| Any Phosh system with Flatpak | `Musette-0.1.0-aarch64.flatpak` |

```sh
sudo apt install ./musette_0.1.0-1_arm64.deb              # DEB
flatpak install --user ./Musette-0.1.0-aarch64.flatpak    # Flatpak
```

## What's in it

- Home: YouTube Music's shelves, loaded page by page
- Search with suggestions, history and filters
- Album, playlist and artist pages
- Library: liked songs, followed artists, most played, your playlists, the playlists and mixes
  of your YouTube account, saved albums and playlists, recently added
- Mini player and Now Playing with seek, shuffle, repeat, like and the queue
- Likes sync to YouTube when you are signed in
- Playback controls on the lock screen and in Phosh (MPRIS)
- Background playback: closing the window keeps the music going; **Quit** in the window menu
  stops it
- Sign-in through Google's page or with a cookie, several accounts
- Every language SimpMusic is translated into

## Known limitations

- ARM64 phones only.
- No lyrics, downloads or settings screens yet.
- Signing in through Google's page in the Flatpak has not been checked on the phone yet; sign-in
  with a cookie works as a fallback.
- The DEB and the Flatpak keep separate profiles.

## Tested

On the FuriPhone FLX1s with FuriOS 14, without signing in, in a separate profile: both the DEB
and the Flatpak install, start, load Home, open an album and play it. The DEB passes lintian.

## Sources and licenses

Musette is licensed under the GNU General Public License, version 3. `musette-0.1.0-sources.tar.gz`
is this project at the release commit. The complete corresponding source of everything in the
packages (SimpMusic and its core, the Java runtime, the Java libraries, and libmpv with the
libraries built into the Flatpak) is `musette-0.1.0-corresponding-sources.tar.part*`;
`SOURCES.md` explains how to join the parts. The licences of the Java libraries are listed in
[THIRD-PARTY.md](https://github.com/x-vlad-x/musette/blob/v0.1.0/THIRD-PARTY.md).

Musette is independent of SimpMusic and Google. YouTube and YouTube Music are trademarks of
Google LLC.
