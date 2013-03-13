package edu.knowitall
package nlpweb
package tool

import scala.Array.canBuildFrom
import scala.collection.JavaConversions.iterableAsScalaIterable
import common.Timing
import edu.washington.cs.knowitall.argumentidentifier.ConfidenceMetric
import edu.knowitall.chunkedextractor.{BinaryExtractionInstance, Nesty, Relnoun}
import edu.washington.cs.knowitall.extractor.{R2A2, ReVerbExtractor}
import edu.washington.cs.knowitall.extractor.conf.ReVerbOpenNlpConfFunction
import edu.washington.cs.knowitall.nlp.ChunkedSentence
import edu.washington.cs.knowitall.nlp.extraction.ChunkedBinaryExtraction
import edu.knowitall.ollie.Ollie
import edu.knowitall.ollie.confidence.OllieConfidenceFunction
import edu.knowitall.openparse
import edu.knowitall.openparse.OpenParse
import edu.knowitall.tool.chunk.{ChunkedToken, OpenNlpChunker}
import edu.knowitall.tool.parse.MaltParser
import edu.knowitall.tool.stem.{Lemmatized, MorphaStemmer}
import edu.knowitall.tool.tokenize.Token
import edu.washington.cs.knowitall.util.DefaultObjects
import unfiltered.request.HttpRequest
import knowitall.srl.SrlExtractor
import edu.knowitall.collection.immutable.Interval

object ExtractorIntent extends ToolIntent("extractor", List("reverb", "relnoun", "nesty", "openparse", "ollie-clear", "ollie-malt", "srl")) {
  override val info = "Enter sentences from which to extract relations, one per line."
  lazy val sentenceDetector = DefaultObjects.getDefaultSentenceDetector()

  lazy val ollieExtractor = new Ollie()
  lazy val ollieConfidence = OllieConfidenceFunction.loadDefaultClassifier()

  lazy val openparseExtractor = {
    OpenParse.withDefaultModel()
  }
  lazy val reverbExtractor = new ReVerbExtractor()
  lazy val nestyExtractor = new Nesty()
  lazy val r2a2Extractor = new R2A2()
  lazy val relnounExtractor = new Relnoun()

  lazy val srlExtractor = new SrlExtractor(SrlIntent.clearSrl)

  lazy val reverbConfidence = new ReVerbOpenNlpConfFunction()
  lazy val r2a2Confidence = new ConfidenceMetric()

  lazy val chunker = ChunkerIntent.opennlpChunker

  implicit def lemmatized2token(lemmatized: Lemmatized[ChunkedToken]): Token = {
    lemmatized.token
  }

  implicit def tokens2chunkedSentence(tokens: Seq[Lemmatized[ChunkedToken]]): ChunkedSentence = {
    new ChunkedSentence(tokens.map(_.token.string).toArray, tokens.map(_.token.postag).toArray, tokens.map(_.token.chunk).toArray)
  }

  def getExtractor(name: String): Seq[Lemmatized[ChunkedToken]] => List[(String, Iterable[Extraction])] = {
    def triple(conf: Double)(extr: ChunkedBinaryExtraction): Extraction =
      new Extraction(conf, extr.getArgument1.getText, extr.getRelation.getText, extr.getArgument2.getText)
    def tripleChunked(conf: Double)(inst: BinaryExtractionInstance[ChunkedToken]): Extraction = 
      new Extraction(conf, inst.extr.arg1.text, inst.extr.rel.text, inst.extr.arg2.text)
    def tripleOpenParse(conf: Double)(extr: openparse.extract.DetailedExtraction): Extraction =
      new Extraction(conf, extr.arg1Text, extr.relText, extr.arg2Text)
    def tripleOllie(conf: Double)(inst: ollie.OllieExtractionInstance): Extraction =
      new Extraction(conf, inst.extr.arg1.text, inst.extr.rel.text, inst.extr.arg2.text)

    s: Seq[Lemmatized[ChunkedToken]] => List((name, name match {
      case "ollie-clear" => {
        val extrs = ollieExtractor.extract(ParserIntent.clearParser.dependencyGraph(s.iterator.map(_.string).mkString(" "))).toList
        val confs = extrs map ollieConfidence.getConf
        extrs map { extr => tripleOllie(ollieConfidence(extr))(extr) }
      }
      case "ollie-malt" => {
        val extrs = ollieExtractor.extract(ParserIntent.maltParser.dependencyGraph(s.iterator.map(_.string).mkString(" "))).toList
        extrs map { extr => tripleOllie(ollieConfidence(extr))(extr) }
      }
      case "openparse" => openparseExtractor.extract(ParserIntent.maltParser.dependencyGraph(s.iterator.map(_.string).mkString(" "))).toSeq.map { case(conf, extr) => tripleOpenParse(conf)(extr) }
      case "reverb" =>
        val extrs: List[ChunkedBinaryExtraction] = reverbExtractor.extract(s).toList
        val confs: List[Double] = extrs map reverbConfidence.getConf
        extrs.map{ extr => triple(reverbConfidence.getConf(extr))(extr) }
      case "nesty" => nestyExtractor.extract(s).map(ex => tripleChunked(0.0)(ex))
      case "r2a2" =>
        val extrs: List[ChunkedBinaryExtraction] = r2a2Extractor.extract(s).toList
        extrs.map{ extr => triple(r2a2Confidence.getConf(extr))(extr) }
      case "relnoun" => relnounExtractor.extract(s).map(ex => tripleChunked(0.0)(ex))
      case "srl" => {
        val parser = ParserIntent.clearParser
        val graph = parser.dependencyGraph(s.iterator.map(_.token.string).mkString(" "))
        srlExtractor.apply(graph).map { extraction =>
          new Extraction(0.0, extraction.arg1.text, extraction.relation.text, extraction.arg2s.map(_.text).mkString(", "))
        }
      }
    }))
  }

  override def config[A](req: unfiltered.request.HttpRequest[A], tool: String) = {
    val currentExtractor = tool
    (for (extractor <- this.tools) yield {
      val key = "check_" + extractor
      "<input name=\"check_" + extractor + "\" type=\"checkbox\" value=\"true\"" + (if (extractor == currentExtractor || (req.parameterNames contains key) && req.parameterValues(key).headOption == Some("true")) """checked="true" """ else "") + " /> " + extractor + "<br />"
    }).mkString("\n")
  }

  override def post[A](tool: String, text: String, params: Map[String, String]) = {
    case class ExtractionSet(sentence: String, extractions: Seq[(String, Iterable[Extraction])])

    def chunk(string: String) = chunker.synchronized {
      sentenceDetector.sentDetect(string).map {
        string => chunker.chunk(string) map MorphaStemmer.lemmatizeToken
      }
    }

    def buildTable(set: ExtractionSet) = {
      "<table><tr><th colspan=\"4\">" + set.sentence + "</th></tr>" + set.extractions.map(extractions => "<tr><th colspan=\"4\">" + extractions._1 + " extractions" + "</th></tr>" + extractions._2.map {
        case Extraction(conf, _, arg1, rel, arg2) =>
          "<tr><td>" + ("%1.2f" format conf) + "</td><td>" + arg1 + "</td><td>" + rel + "</td><td>" + arg2 + "</td></tr>"
      }.mkString("\n")).mkString("\n") + "</table><br/><br/>"
    }

    val extractorName = tool

    // create an extractor that extracts for all checked extractors
    def extractor(sentence: Seq[Lemmatized[ChunkedToken]]) =
      (for {
        key <- params.keys; if key.startsWith("check_")
        extrs <- getExtractor(key.drop(6))(sentence)
      } yield (extrs)).toSeq.sortBy { case (extr, extrs) => this.tools.indexOf(extr) }

    val (chunkTime, chunked) = Timing.time(chunk(text))
    val (extractionTime, extractions) = Timing.time(chunked.map(chunked => ExtractionSet(chunked.getTokensAsString, extractor(chunked))))
    ("chunking: " + Timing.Milliseconds.format(chunkTime) + "\n" +
      "extracting: " + Timing.Milliseconds.format(extractionTime),
      "<p>" + extractions.map(_.extractions).flatten.map(_._2).flatten.size + " extraction(s):</p>" + extractions.map(buildTable(_)).mkString("\n"))
  }
}

case class Extraction(conf: Double, context: Option[Part] = None, arg1: Part, rel: Part, arg2: Part) {
  def this(conf: Double, arg1: String, rel: String, arg2: String) = this(conf, None, new Part(arg1), new Part(rel), new Part(arg2))
}
case class Part(string: String) 