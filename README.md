# Atomstream

TUIs are easy and fun to code, but they are not easy to share with friends or colleagues. It is also hard to implement accessibility features for TUIs.

Atomstream is a drop-in replacement for [charm.clj][charm], which also renders to the Web with [hyperlith][hyperlith]. It lets you code for the terminal, and share on the Web.

[charm]: https://github.com/TimoKramer/charm.clj
[hyperlith]: https://github.com/andersmurphy/hyperlith

![Atomstream demo](doc/demo.gif)

## Usage

1. In your charm.clj program, replace `charm` with `atomstream`:

```
(require '[atomstream.program :as program]   ;; was charm.program
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

## Web-only

You can use Atomstream to run a TUI without a TUI, if that makes any sense!

Use `atomstream.program/run-web-only` to start the application without a terminal interface. (The Web view will, of course, still look and act like a terminal.) This is useful for running the program on a server.

You will probably want to load many small programs dynamically: see the [launcher][doc/examples/src/examples/launcher.clj] example for how to do this using [sci][sci].

[sci]: https://github.com/babashka/sci

## REPL Development

```
bin/launchpad
```

Use `atomstream.program/run-async` as described in the [corresponding section](https://github.com/TimoKramer/charm.clj#repl-development) for 
charm.clj.

See also [launchpad](https://github.com/lambdaisland/launchpad) for more.

# Roadmap
- Mouse support
- Accessibility
- Web-native components such as dropdowns?
- Themes
- (Maybe) custom components with special rendering for the Web
