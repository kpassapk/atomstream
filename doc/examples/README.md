# Atomstream examples

Browse examples

```
bb launcher
```

## Run Web-only

```
clj -J--enable-native-access=ALL-UNNAMED -M:sci -m examples.launcher --web-only
```

## Developing

```
bin/launchpad
```

You may want to add to `deps.local.edn`, if you are developing the launcher.

```
{:launchpad/aliases [:sci]}
```

