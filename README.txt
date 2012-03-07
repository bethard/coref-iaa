This project contains code for calculating inter-annotator agreement for entity
coreference annotations. Currently, input is restricted to Knowtator XML files,
and agreement calculations are based on the CoNLL 2012 scorer
(http://conll.cemantix.org/2012/software.html).

To compare two directories of Knowtator coreference XML files, run:

  ./knowtator_dir_agreement.sh path/to/annotator1/dir/ path/to/annotator2/dir/

To compare two single files, run:

  scala ParseKnowtatorCoref.scala file1.xml > 1.spans
  scala ParseKnowtatorCoref.scala file2.xml > 2.spans
  perl entity_span_agreement.pl 1.spans 2.spans

To exclude certain type of mention classes, e.g. 'markable', you can add the
argument "--exclude <class>", e.g.

  ./knowtator_dir_agreement.sh dir1 dir2 --exclude markable

or:

  scala ParseKnowtatorCoref.scala --exclude Appositive file1.xml > 1.spans

You can add more than one "--exclude <class>" argument if you want to exclude
multiple classes.
