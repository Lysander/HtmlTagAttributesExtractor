# Generate Extension Methods for all HTML attributes for fritz2

This small CLI tool is only used to generate some extension methods for all HTML tag attributes for the fabulous [fritz2](https://github.com/jwstegemann/fritz2) project defined in the
file ``dom.kt`` of the ``org.w3c.dom`` package.

Just start the application and pass the path to the ``dom.kt`` file as command line parameter.

Assuming the file has been copied into the project folder, just do this:
```text
./gradlew run --args="dom.kt"
```

The tool will just print the result to ``STDOUT`` like this:
```text
> Task :run
fun Tag<HTMLFormControlsCollection>.value(value: String) = attr("value", value)
fun Tag<HTMLFormControlsCollection>.value(value: Flow<String>) = attr("value", value)
fun Tag<HTMLOptionsCollection>.selectedIndex(value: Int) = attr("selectedIndex", value)
fun Tag<HTMLOptionsCollection>.selectedIndex(value: Flow<Int>) = attr("selectedIndex", value)
fun Tag<HTMLElement>.title(value: String) = attr("title", value)
fun Tag<HTMLElement>.title(value: Flow<String>) = attr("title", value)
...
```

Then copy the new methods to the fritz2 project file ``attributes.kt`` in the ``dev.fritz2.core`` package.
