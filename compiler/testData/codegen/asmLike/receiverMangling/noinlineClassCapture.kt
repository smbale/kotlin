// LOCAL_VARIABLE_TABLE

class Foo {
    fun foo() {
        block {
            this@Foo
        }
    }

    inner class Bar {
        fun bar() {
            block {
                this@Foo
                this@Bar

                block {
                    this@Foo
                    this@Bar
                }
            }
        }
    }
}

fun block(block: () -> Unit) = block()