# Link Lyric

A Minecraft mod that displays music lyrics in-game.

中文版见 [README.md](README.md)。

## Supported data sources

| Data source | Form | Status | Repository | Installation |
|---|---|---|---|---|
| Salt Player | Plugin | ✅ | [Link-MC](https://github.com/beipuo/Link-MC) | [Link-MC](https://github.com/beipuo/Link-MC) |
| Windows SMTC | — | ⬛️ | ⬛️ | ⬛️ |
| MPD / foobar | — | ⬛️ | ⬛️ | ⬛️ |
| Local LRC/TTML | — | ⬛️ | ⬛️ | ⬛️ |

⬛️ = not implemented yet. The Salt Player bridge is currently the only working source; you can write your own plugin to feed lyrics from other players.

## Build

```powershell
# Kernel unit tests only (no Minecraft download required)
.\gradlew.bat :common:test

# Full build
.\gradlew.bat build

# Launch client
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
```

See [Build and Environment](docs/BUILD.md) for details.

## Documentation

The documents below are currently written in Chinese.

| Document | Contents |
|---|---|
| [Design Goals](docs/DESIGN.md) | Layering rationale, repository layout, the seven build guards |
| [Kernel](docs/KERNEL.md) | `core` package responsibilities and the capability model |
| [Rendering](docs/RENDERING.md) | Layout, per-character coloring, in-glyph gradients, emphasis animation, drag interaction |
| [Protocol](docs/PROTOCOL.md) | Inter-process contract between adapters and the mod (v2) |
| [Build and Environment](docs/BUILD.md) | Branch/version matrix, local JDK, proxy, two common pitfalls |
| [Roadmap](docs/ROADMAP.md) | Completed work, the 26.1.2 rendering migration plan, adapter ordering advice |
| [Credits and License](docs/CREDITS.md) | Upstream projects, differences from upstream, per-file licensing |

## Credits

**applemusic-like-lyrics (AMLL)** — <https://github.com/amll-dev/applemusic-like-lyrics>
**Lyricon** (Tomakino / Proify) — <https://github.com/tomakino/lyricon>
**HyperMusicCover** — <https://github.com/zyl6932/HyperMusicCover>

For the specific parameters and code taken from each project, see [Credits and License](docs/CREDITS.md).

### License

This repository is **licensed per file**, not under a single license:

| Scope | License |
| --- | --- |
| All code except the files listed below | MIT (see `LICENSE`) |
| `effect/EmphasizeGlowEffect`, `EmphasisGroup`, `EmphasisLayout`, `WaveLiftEffect`, `EmpEasing` | Apache-2.0 (derived from Lyricon) |

Upstream licenses:

| Project | License |
| --- | --- |
| applemusic-like-lyrics | AGPL-3.0 |
| Lyricon | Apache-2.0 |
| HyperMusicCover | AGPL-3.0 |

Derived files are listed individually in `NOTICE`. The full Apache-2.0 text is at `third_party/lyricon/LICENSE`.
