import org.xml.sax.SAXParseException
import scala.collection.{Set,Seq,Map}
import scala.collection.mutable
import scala.util.control.Exception
import scala.xml.XML

object ParseKnowtatorCoref {

  case class Mention(spans: Seq[(Int, Int)])
  case class Entity(mentions: Set[Mention])

  def main(args: Array[String]) = {

    // container for options parsed from the command line
    case class Options(
      write: (String, Set[Mention], Set[Entity]) => Unit = writeSpans,
      exclude: Set[String] = Set.empty,
      fileNames: List[String]
    )

    // usage message
    val usage = (
      "usage: java " + this.getClass.getName +
      " [--write-spans|--write-conll] [--exclude mentionClass] " +
      "file.knowtator.xml [file.knowtator.xml ...]")

      // method for parsing options from the command line
      def parseArgs(args: List[String]): Options = {
        args match {
          case "--write-conll" :: tail => parseArgs(tail).copy(write=this.writeConll)
          case "--write-spans" :: tail => parseArgs(tail).copy(write=this.writeSpans)
          case "--exclude" :: value :: tail => {
            val options = parseArgs(tail)
            options.copy(exclude=options.exclude + value)
          }
          case head :: tail if head.startsWith("--") => throw new IllegalArgumentException(
            "invalid option: %s\n%s".format(head, usage))
          case head :: Nil => Options(fileNames=List(head))
          case head :: tail => {
            val options = parseArgs(tail)
            options.copy(fileNames=head :: options.fileNames)
          }
          case Nil => throw new IllegalArgumentException("missing arguments\n%s".format(usage))
        }
      }

    // parse options from the command line
    val options = parseArgs(args.toList)

    // prepare error messages for any invalid XML files
    val fileNameXMLOptionIter =
      for (fileName <- options.fileNames.iterator) yield {
        val catcher = Exception.catching(classOf[SAXParseException])
        val xmlOption = catcher.opt(XML.loadFile(fileName))
        if (xmlOption.isEmpty) {
          System.err.println("WARNING: skipping invalid XML file " + fileName)
        }
        fileName -> xmlOption
      }

    // read the XML file from standard input
    for ((fileName, xmlOption) <- fileNameXMLOptionIter; annotationsElem <- xmlOption) {
      System.err.printf("Processing %s\n", fileName)

      // identify the source file name
      val Seq(textSource) = annotationsElem \ "@textSource"

      // parse the mentions and their spans
      val idMentionOptions = for (annotationElem <- annotationsElem \ "annotation") yield {
        val idAttrSeq = annotationElem \ "mention" \ "@id"
        val spans = for (spanElem <- annotationElem \ "span") yield {
          val Seq(startAttr) = spanElem \ "@start"
          val Seq(endAttr) = spanElem \ "@end"
          startAttr.text.toInt -> endAttr.text.toInt
        }
        if (idAttrSeq.isEmpty) {
          System.err.println("WARNING: ignoring annotation with no <mention id=...>:")
          System.err.println(annotationElem)
          None
        } else if (idAttrSeq.size > 1) {
          System.err.println("WARNING: ignoring annotation with many <mention id=...>:")
          System.err.println(annotationElem)
          None
        } else if (spans.isEmpty) {
          System.err.println("WARNING: ignoring span-less annotation:")
          System.err.println(annotationElem)
          None
        } else {
          // just take the largest span; CoNLL script doesn't understand discontinuous
          val sortedSpans = spans.sorted
          val span = sortedSpans.head._1 -> sortedSpans.last._2
          Some(idAttrSeq.head.text -> Mention(Seq(span)))
        }
      }

      // map mention IDs to mention objects
      val idMentionMap = idMentionOptions.flatten.toMap

      // map slot IDs to the mentions contained in this slot
      val idSlotMentions = for (slotMentionElem <- annotationsElem \ "complexSlotMention") yield {
        val Seq(idAttr) = slotMentionElem \ "@id"
        val mentionIds = (slotMentionElem \ "complexSlotMentionValue").map(_ \ "@value" text)
        idAttr.text -> mentionIds.map(idMentionMap)
      }
      val idSlotMentionsMap = idSlotMentions.toMap

      // collect annotated sets of mentions (a single mention may be included in multiple sets)
      val mentionSetOptions = for {
        classMentionElem <- annotationsElem \ "classMention"
        if !options.exclude.contains(classMentionElem \ "mentionClass" \ "@id" text)
      } yield {
        val Seq(idAttr) = classMentionElem \ "@id"
        val id = idAttr.text
        val slotIds = (classMentionElem \ "hasSlotMention").map(_ \ "@id" text)
        if (!idMentionMap.contains(id)) {
          System.err.printf(
            "WARNING: ignoring missing '%s' mention: %s\n",
            classMentionElem \ "mentionClass" \ "@id" text,
            id)
        }
        for (mention <- idMentionMap.get(id))
          yield Set(mention) ++ slotIds.flatMap(idSlotMentionsMap).toSet
      }
      val mentionSets = mentionSetOptions.flatten

      // merge sets of mentions so that each mention is included in exactly one set
      val mergedMentionSets = mutable.Set.empty[mutable.Set[Mention]]
      val todo = mutable.ListBuffer.empty ++ mentionSets
      while (!todo.isEmpty) {
        val mentionSet = mutable.Set.empty ++ todo.remove(0)
        val shouldBeMerged = (m : mutable.Set[Mention]) => (m & mentionSet).size > 0
        while (mergedMentionSets.exists(shouldBeMerged)) {
          for (existingSet <- mergedMentionSets.filter(shouldBeMerged)) {
            mergedMentionSets.remove(existingSet)
            mentionSet ++= existingSet
          }
        }
        mergedMentionSets += mentionSet
      }

      // create entities from mention sets
      val entities = for (mentions <- mergedMentionSets) yield Entity(mentions)

      // make sure that all mentions are in exactly one entity
      val message = "each mention should be in exactly one entity; found %s in:\n%s\n"
      for ((id, mention) <- idMentionMap) {
        val containing = entities.filter(_.mentions.contains(mention))
        assert(containing.size == 1 || (!options.exclude.isEmpty && containing.size == 0),
               message.format(id, containing.mkString("\n")))
      }

      // write out the mentions and entities
      val mentions = idMentionMap.values.toSet
      options.write(textSource.text, mentions, entities)
    }
  }

  // write out one line per entitiy, with the character offsets of its spans
  def writeSpans(source: String, mentions: Set[Mention], entities: Set[Entity]) = {
    for (entity <- entities.toSeq.sortBy(_.mentions.flatMap(_.spans).min)) {
      val spans = entity.mentions.flatMap(_.spans).toSeq.sorted
      val spanStrings = for ((begin, end) <- spans) yield "%d-%d".format(begin, end)
      println((source +: spanStrings).mkString("\t"))
    }
  }

  // print CoNLL format lines for mentions (See conll.cemantix.org/2012/data.html)
  // FIXME: output one line per token
  // FIXME: use token offsets, not character offsets
  def writeConll(source: String, mentions: Set[Mention], entities: Set[Entity]) = {
    // get entity numbers for each mention
    val mentionEntityIds =
      for ((entity, index) <- entities.zipWithIndex; mention <- entity.mentions)
      yield mention -> index
    val mentionEntityIdMap = mentionEntityIds.toMap

    import Ordering.Implicits.seqDerivedOrdering 
    for (mention <- mentions.toSeq.sortBy(_.spans); entityId <- mentionEntityIdMap.get(mention)) {
      val documentId = source
      val partNumber = "0"
      val beginWordNumber = mention.spans.map(_._1).min
      val endWordNumber = mention.spans.map(_._2).max
      val word = "-"
      val partOfSpeech = "-"
      val parseBit = "-"
      val predicateLemma = "-"
      val predicateFramesetId = "-"
      val wordSense = "-"
      val speakerAuthor = "-"
      val namedEntities = "-"
      val beginCoreference = "(%s".format(entityId)
      val endCoreference = "%s)".format(entityId)
      def getColumns(wordNumber: Int, corefString: String): Seq[String] = Seq(
        documentId,
        partNumber,
        wordNumber.toString,
        word,
        partOfSpeech,
        parseBit,
        predicateLemma,
        predicateFramesetId,
        wordSense,
        speakerAuthor,
        namedEntities,
        corefString)

      println(getColumns(beginWordNumber, beginCoreference).mkString("\t"))
      println(getColumns(endWordNumber, endCoreference).mkString("\t"))
    }
  }
}
