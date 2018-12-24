/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.util

import java.util.regex.{Pattern, PatternSyntaxException}

import scala.collection.mutable

import org.apache.commons.lang.StringUtils.isNotBlank

import org.apache.spark.sql.AnalysisException
import org.apache.spark.unsafe.types.UTF8String


object StringUtils {

  /**
   * Validate and convert SQL 'like' pattern to a Java regular expression.
   *
   * Underscores (_) are converted to '.' and percent signs (%) are converted to '.*', other
   * characters are quoted literally. Escaping is done according to the rules specified in
   * [[org.apache.spark.sql.catalyst.expressions.Like]] usage documentation. An invalid pattern will
   * throw an [[AnalysisException]].
   *
   * @param pattern the SQL pattern to convert
   * @return the equivalent Java regular expression of the pattern
   */
  def escapeLikeRegex(pattern: String): String = {
    val in = pattern.toIterator
    val out = new StringBuilder()

    def fail(message: String) = throw new AnalysisException(
      s"the pattern '$pattern' is invalid, $message")

    while (in.hasNext) {
      in.next match {
        case '\\' if in.hasNext =>
          val c = in.next
          c match {
            case '_' | '%' | '\\' => out ++= Pattern.quote(Character.toString(c))
            case _ => fail(s"the escape character is not allowed to precede '$c'")
          }
        case '\\' => fail("it is not allowed to end with the escape character")
        case '_' => out ++= "."
        case '%' => out ++= ".*"
        case c => out ++= Pattern.quote(Character.toString(c))
      }
    }
    "(?s)" + out.result() // (?s) enables dotall mode, causing "." to match new lines
  }

  private[this] val trueStrings = Set("t", "true", "y", "yes", "1").map(UTF8String.fromString)
  private[this] val falseStrings = Set("f", "false", "n", "no", "0").map(UTF8String.fromString)

  // scalastyle:off caselocale
  def isTrueString(s: UTF8String): Boolean = trueStrings.contains(s.toLowerCase)
  def isFalseString(s: UTF8String): Boolean = falseStrings.contains(s.toLowerCase)
  // scalastyle:on caselocale

  /**
   * This utility can be used for filtering pattern in the "Like" of "Show Tables / Functions" DDL
   * @param names the names list to be filtered
   * @param pattern the filter pattern, only '*' and '|' are allowed as wildcards, others will
   *                follow regular expression convention, case insensitive match and white spaces
   *                on both ends will be ignored
   * @return the filtered names list in order
   */
  def filterPattern(names: Seq[String], pattern: String): Seq[String] = {
    val funcNames = scala.collection.mutable.SortedSet.empty[String]
    pattern.trim().split("\\|").foreach { subPattern =>
      try {
        val regex = ("(?i)" + subPattern.replaceAll("\\*", ".*")).r
        funcNames ++= names.filter{ name => regex.pattern.matcher(name).matches() }
      } catch {
        case _: PatternSyntaxException =>
      }
    }
    funcNames.toSeq
  }

  /**
   * Split the text into one or more SQLs with comments reserved
   *
   * Highlighted Corner Cases: semicolon in double quotes, single quotes or inline comments.
   * Expected Behavior: The blanks will be trimed and a blank line will be omitted.
   *
   * @param text One or more SQLs separated by semicolons
   * @return the trimmed SQL array (Array is for Java introp)
   */
  def split(text: String): Array[String] = {
    val D_QUOTE: Char = '"'
    val S_QUOTE: Char = '\''
    val Q_QUOTE: Char = '`'
    val SEMICOLON: Char = ';'
    val ESCAPE: Char = '\\'
    val DOT = '.'
    val SINGLE_COMMENT = "--"
    val BRACKETED_COMMENT_START = "/*"
    val BRACKETED_COMMENT_END = "*/"
    val FORWARD_SLASH = "/"

    // quoteFlag acts as an enum of D_QUOTE, S_QUOTE, DOT
    // * D_QUOTE: the cursor stands on a doulbe quoted string
    // * S_QUOTE: the cursor stands on a single quoted string
    // * DASH: the cursor stands in the SINGLE_COMMENT
    // * FORWARD_SLASH: the cursor stands in the BRACKETED_COMMENT
    // * DOT: default value for other cases
    var quoteFlag: Char = DOT
    var cursor: Int = 0
    val ret: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
    var currentSQL: mutable.StringBuilder = mutable.StringBuilder.newBuilder

    while (cursor < text.length) {
      val current: Char = text(cursor)

      text.substring(cursor) match {
        // if it stands on the opening of a bracketed comment, consume 2 characters
        case remaining if remaining.startsWith(BRACKETED_COMMENT_START) =>
          ret += currentSQL.toString.trim
          cursor += 2

        // if it stands on the opening of inline comment, move cursor at the end of this line
        case remaining if quoteFlag == DOT && remaining.startsWith(SINGLE_COMMENT) =>
          cursor += remaining.takeWhile(x => x != '\n').length

        // if it stands on a normal semicolon, stage the current sql and move the cursor on
        case remaining if quoteFlag == DOT && current == SEMICOLON =>
          ret += currentSQL.toString.trim
          currentSQL.clear()
          cursor += 1

        // if it stands on the openning of quotes, mark the flag and move on
        case remaining if quoteFlag == DOT
                          && List(D_QUOTE, S_QUOTE, Q_QUOTE).contains(current) =>
          quoteFlag = current
          currentSQL += current
          cursor += 1
        // if it stands on '\' in the quotes, consume 2 characters to avoid the ESCAPE of " or '
        case remaining if remaining.length >= 2 && quoteFlag != DOT && current == ESCAPE =>
          currentSQL.append(remaining.take(2))
          cursor += 2
        // if it stands on the ending of quotes, clear the flag and move on
        case remaining if List(D_QUOTE, S_QUOTE, Q_QUOTE).contains(quoteFlag)
                          && current == quoteFlag =>
          quoteFlag = DOT
          currentSQL += current
          cursor += 1

        // move on and push the char to the currentSQL
        case _ =>
          currentSQL += current
          cursor += 1
      }
    }

    ret += currentSQL.toString.trim
    ret.filter(isNotBlank).toArray
  }


  /**
   * Concatenation of sequence of strings to final string with cheap append method
   * and one memory allocation for the final string.
   */
  class StringConcat {
    private val strings = new mutable.ArrayBuffer[String]
    private var length: Int = 0

    /**
      * Appends a string and accumulates its length to allocate a string buffer for all
      * appended strings once in the toString method.
      */
    def append(s: String): Unit = {
      if (s != null) {
        strings.append(s)
        length += s.length
      }
    }

    /**
      * The method allocates memory for all appended strings, writes them to the memory and
      * returns concatenated string.
      */
    override def toString: String = {
      val result = new java.lang.StringBuilder(length)
      strings.foreach(result.append)
      result.toString
    }
  }
}
