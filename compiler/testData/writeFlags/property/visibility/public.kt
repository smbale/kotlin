class Foo {
  public inner class MyClass() {
  }
}

// TESTED_OBJECT_KIND: property
// TESTED_OBJECTS: Foo$MyClass, $this
// FLAGS: ACC_FINAL, ACC_SYNTHETIC
