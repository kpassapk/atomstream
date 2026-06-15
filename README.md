# Atomstream

TUIs are easy and fun to code, but they are not easy to share with friends or colleagues. It is also hard to implement accessibility features for TUIs.

Atomstream is a drop-in replacement for [charm.clj][charm], which also renders to the Web with [hyperlith][hyperlith].

[charm]: https://github.com/TimoKramer/charm.clj
[hyperlith]: https://github.com/andersmurphy/hyperlith

![Atomstream demo](doc/demo.gif)

## Usage

1. In your charm.clj program, replace `charm` with `atomstream`:

```
(require '[atomstream.program :as program]   ;; was charm.program — run/run-async also render to the web
         '[atomstream.style.core :as style]  ;; was charm.style.core
         '[atomstream.message :as msg])      ;; was charm.message
...
```

2. Start the program with `program/run` as usual. 
3. Open your browser to port 8080

## Goals

1. Stay as close as possible to charm.clj
2. Render correctly and unobtrusively to the Web (with themes perhaps?)
3. Use richer Web functionality selectively

## Try it!

Run the example launcher:

```
cd doc/examples
bb launcher
```
Then open http://localhost:8080

You can also run individual examples:

```
cd doc/examples
bb tasks
bb cheatsheet       # etc
```

# Roadmap
- Mouse support
- Web-native components such as dropdowns?
- Themes
- CLI
- (Maybe) custom components with special rendering for the Web
