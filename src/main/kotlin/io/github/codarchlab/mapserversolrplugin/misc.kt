/*
 * Copyright 2016 David Smiley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.codarchlab.mapserversolrplugin


// Copy of Kotlin's same for Closeable
inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
  var closed = false
  try {
    return block(this)
  } catch (e: Exception) {
    closed = true
    try {
      this?.close()
    } catch (closeException: Exception) {
      // eat the closeException as we are already throwing the original cause
      // and we don't want to mask the real exception

      // TODO on Java 7 we should call
      // e.addSuppressed(closeException)
      // to work like try-with-resources
      // http://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html#suppressed-exceptions
    }
    throw e
  } finally {
    if (!closed) {
      this?.close()
    }
  }
}