---
name: "minimize-upstream-impact"
description: "Guides code changes to minimize upstream merge conflicts. Invoke when adding features to forked/derived repos, modifying vendored code, or extending upstream libraries."
---

# Minimize Upstream Impact

When working with forked repositories, vendored libraries, or any codebase that tracks an upstream source, the goal is to introduce custom functionality **without** making invasive changes to upstream files. This skill provides principles, patterns, and a checklist for achieving that.

## Core Principles

### 1. New Files = Zero Merge Risk
Every custom class, interface, or utility should be a **new file**. New files never conflict with upstream merges.

**Bad:** Adding a `DaemonManager` inner class inside `Server.java`
**Good:** Creating a separate `DaemonManager.java` in the same package

### 2. Transport/Protocol Decoupling
Never hardcode a transport mechanism (sockets, streams, files) into core logic. Abstract behind interfaces or use the most generic base type.

```java
// BAD: tightly coupled to LocalSocket
public ControlChannel(LocalSocket socket) { ... }

// GOOD: works with any stream source
public ControlChannel(InputStream in, OutputStream out) { ... }
```

### 3. Configuration Separation
Keep custom configuration out of upstream `Options` / `Config` classes. Create your own config object that wraps or complements the upstream one.

```java
// BAD: adding custom port field to upstream Options
public class Options {
    private int port; // custom field
    private boolean daemonMode; // custom field
}

// GOOD: separate config
public class DaemonOptions {
    private int port;
    private boolean daemonMode;
}
```

### 4. Protocol Extension Safety
When extending a protocol (message types, commands, packets):

- **Reserve ranges**: Use message type ranges that won't collide with upstream additions
- **Check type width**: If message types serialize as `byte` (signed, -128..127), values above 127 require unsigned handling
- **Document your range**: Add comments marking where custom extensions begin

```java
// BAD: collides with potential upstream TYPE_* values (0..22)
public static final int TYPE_CUSTOM_COMMAND = 25;

// GOOD: starts at a safe offset
public static final int TYPE_CUSTOM_COMMAND = 201;
```

### 5. Copy Over Reflection
Reflection-based cloning or field access breaks when upstream refactors. Use explicit copy constructors or builder patterns.

```java
// BAD: reflection-based clone (breaks if field renamed/removed)
private static Options cloneOptionsWithDisplayId(Options original, int id) {
    Field[] fields = Options.class.getDeclaredFields();
    for (Field f : fields) { ... } // fragile!
}

// GOOD: explicit copy constructor / method
public Options copyWithDisplayId(int newDisplayId) {
    Options copy = new Options();
    copy.field1 = this.field1;
    copy.displayId = newDisplayId; // override
    ...
    return copy;
}
```

### 6. Delegate Over Modify
Instead of adding `if (customMode) ... else ...` branches throughout upstream code, delegate to a handler.

```java
// BAD: scattered conditionals
// In Controller.handleMessage():
if (type == TYPE_CREATE_VIRTUAL_DISPLAY) {
    ... // 50 lines of custom logic
}
if (type == TYPE_RELEASE_VIRTUAL_DISPLAY) {
    ... // 50 lines of custom logic  
}
// ... 5 more cases

// GOOD: single delegation point
private DaemonCommandHandler daemonHandler;

private boolean handleCustomMessage(ControlMessage msg) {
    return daemonHandler != null && daemonHandler.handle(msg);
}
```

### 7. Entry Point Isolation
If upstream has a single entry point (`main`, `start`, `run`), wrap it rather than modifying it.

```java
// BAD: adding custom loop inside upstream main()
public static void main(String... args) {
    Options options = Options.parse(args);
    DaemonOptions daemonOpts = parseDaemonOptions(args); // polluting main
    if (daemonOpts.isDaemonMode()) {
        while (true) { scrcpy(options); Thread.sleep(1000); } // custom loop inline
    } else {
        scrcpy(options);
    }
}

// GOOD: separate runner
public static void main(String... args) {
    Options options = Options.parse(args);
    DaemonOptions daemonOpts = parseDaemonOptions(args);
    if (daemonOpts.isDaemonMode()) {
        DaemonRunner.run(options, daemonOpts, Server::scrcpy);
    } else {
        scrcpy(options, null);
    }
}
```

## Pre-Modification Checklist

Before touching any upstream file, ask:

- [ ] **Can this be a new file instead?** (90% of the time, yes)
- [ ] **Can I use an interface/extension point?** (observer pattern, strategy pattern, plugin pattern)
- [ ] **Is my config leaking into upstream options?** (create a sibling config class)
- [ ] **Am I using reflection?** (replace with explicit methods)
- [ ] **Does my message type collide?** (check upstream's existing types and pick a safe range)
- [ ] **Can I delegate from a single point?** (one `if` block in one file > scattered logic)

## Post-Modification Verification

1. **Diff review**: Check `git diff --stat upstream/HEAD` — upstream files should show < 30 lines changed each
2. **Search for leaks**: Grep for custom identifiers in upstream files — they should only appear in the delegation point
3. **Build clean**: Verify both the original path (without custom features) and the custom path compile
4. **No reflection**: Ensure no `getDeclaredField`, `getMethod`, `invoke` on upstream classes unless absolutely necessary

## Anti-Patterns (What NOT to Do)

| Anti-Pattern | Why It's Bad | Correct Alternative |
|-------------|-------------|-------------------|
| Modifying upstream constructor signatures | Breaks all upstream callers | Create a factory/builder in your class |
| Adding fields to upstream data classes | Every upstream change = merge conflict | Compose: `class MyConfig { UpstreamConfig base; CustomConfig custom; }` |
| Catching upstream exceptions and swallowing them | Hides bugs during upstream upgrades | Log + rethrow, or wrap with explicit handling |
| Hardcoding upstream internal paths | Breaks on upstream refactor | Use public APIs; if needed, wrap in your own facade |
| Editing upstream comments | Creates spurious diffs | Leave upstream comments untouched; add your own in new files |

## When Upstream Changes Do Touch Your Code

Sometimes upstream modifies code you depend on:

1. **Upstream adds a field/method to a class you extend**: Usually safe — your subclass inherits it
2. **Upstream changes a method signature**: Update your delegation point (usually one file)
3. **Upstream adds a conflicting message type**: Move your custom types to a new range
4. **Upstream extracts an interface**: Your code already uses streams/interfaces, so it should adapt naturally

The more you've followed these principles, the less work upstream merges require.
