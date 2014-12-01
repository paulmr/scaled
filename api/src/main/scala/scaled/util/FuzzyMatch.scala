//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.util

import scaled._

/** Handles fuzzy matching of strings. A fuzzy match means that each character of `glob` appears in
  * the matched string, in order, with zero or more intervening characters. For example: `pnts`
  * fuzzy matches `peanuts`. */
class FuzzyMatch (glob :String) {

  /** Returns the subset of `strs` which fuzzy match `glob`, in order of match quality. */
  def filter (strs :SeqV[String]) :Seq[String] = filter(strs, identity[String])

  /** Returns the subset of `as` which fuzzy match `glob` after being converted to strings via
    * `fn`, in order of match quality. */
  def filter[A] (as :SeqV[A], fn :A => String) :Seq[A] = {
    case class Score[A] (a :A, astr :String, score :Int) extends Comparable[Score[A]] {
      def compareTo (other :Score[A]) = {
        val r0 = Integer.compare(score, other.score)
        if (r0 != 0) r0
        else {
          val r1 = Integer.compare(astr.length, other.astr.length)
          if (r1 != 0) r1 else astr.compareTo(other.astr)
        }
      }
    }
    val sb = Seq.builder[Score[A]](as.size)
    val iter = as.iterator() ; while (iter.hasNext()) {
      val a = iter.next() ; val astr = fn(a) ; val ascore = score(astr)
      if (ascore > 0) sb += Score(a, astr, -ascore)
    }
    sb.build().sorted.map(_.a)
  }

  /** Returns a match score `> 0` if `glob` fuzzy matches `full`, `0` if it does not match. */
  def score (full :String) :Int = {
    val glen = glob.length ; val flen = full.length
    if (glen == 0) 1
    else if (glen > flen) 0
    else {
      var score = 0 ; var consec = 0
      var gg = 0 ; var lg = adjustCase(glob.charAt(gg))
      var ff = 0 ; while (gg < glen && ff < flen) {
        val lf = adjustCase(full.charAt(ff))
        if (lg == lf) {
          // make consecutive matches that start with the very first character score higher than
          // consecutive matches later in the string
          if (ff == 0) consec += 1
          consec += 1
          score += consec
          gg += 1
          if (gg < glen) lg = adjustCase(glob.charAt(gg))
        } else consec = 0
        ff += 1
      }
      if (gg == glen) score else 0
    }
  }

  protected def adjustCase (c :Char) :Char = c
}

object FuzzyMatch {

  /** Returns a fuzzy matcher on `glob`. If `glob` contains any upper case characters, the match
    * will be case sensitive, otherwise it will be case insensitive. */
  def create (glob :String) :FuzzyMatch = {
    // if the glob string is mixed case, do exact case fuzzy matching
    if (glob.exists(Character.isUpperCase)) new FuzzyMatch(glob)
    // otherwise do case insensitive matching
    else new FuzzyMatch(glob) {
      override def adjustCase (c :Char) = Character.toLowerCase(c)
    }
  }

  /** Alias for [[create]] for Scala clients. */
  def apply (glob :String) = create(glob)
}
