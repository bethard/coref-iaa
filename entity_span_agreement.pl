#!/usr/bin/env perl

use File::Basename;
use Data::Dumper;

BEGIN {
    my $dirname = dirname(__FILE__);
    push(@INC, "$dirname/scorer/v4/lib");
}

use CorScorer;


# Parse a file containing source file names and the offset spans for
# each mention of an entity, formatted one-entity-per-line as:
#   source_name<tab>mention1begin-mention1end<tab>mention2begin-mention2end<tab>...
#   source_name<tab>mention1begin-mention1end<tab>mention2begin-mention2end<tab>...
#   ...
# The result is a hash mapping from each source file name to an array of
# entities, where each entity is an array of mentions and each mention
# is a length-two array of the mention's begin and end offsets
# (inclusive). For example:
#
#   %result = (
#     "doc1.txt" => (
#       [ [1,3], [45,45], [57,62] ],
#       [ [5,5], [25,27] ],
#       ...
#     ),
#     "doc2.txt" => (
#       ...
#     ),
#     ...
#   );
#
# In doc1.txt, there are two entities. The first is composed of 3
# mentions spanning offsets 1 to 3, 45 to 45 and 57 to 62. The second
# is composed of 2 mentions spanning offsets from 5 to 5 and 25 to 27.
sub GetSourceToEntitiesHash
{
    my ($file) = @_;
    my %coref;
    my %ind;

    open (F, $file) || die "Can not open $file: $!";
    while (my $l = <F>) {
        chomp($l);
        my @columns = split(/\t/, $l);
        my $source_name = shift(@columns);
        my @entity;
        for my $span_string (@columns) {
            my @begin_end = split(/-/, $span_string);
            my $begin = $begin_end[0];
            my $end = $begin_end[1];
            push(@entity, [$begin, $end]);
        }
        push(@{$coref{$source_name}}, \@entity);
    }
    return \%coref;
}


# ======================
# Main evaluation script
# ======================

# parse the key and response files to get entities
die "usage: evaluate_entity_spans.pl keys-file response-file\n" unless @ARGV == 2;
my %keyFileEntities = %{GetSourceToEntitiesHash(shift(@ARGV))};
my %responseFileEntities = %{GetSourceToEntitiesHash(shift(@ARGV))};

# define the set of scorers that will be applied
my @scorerNames = ("MUC", "B^3", "CEAFm", "CEAFe");
my %scorers = (
    "MUC" => \&CorScorer::MUCScorer,
    "B^3" => \&CorScorer::BCUBED,
    "CEAFm" => (sub {CorScorer::CEAF(@_, 1)}),
    "CEAFe" => (sub {CorScorer::CEAF(@_, 0)}),
);

my %scorerResults;

# invoke each scorer to compare the key and response entities
print("--------------------------------------------------------------------------\n");
foreach my $scorerName (@scorerNames) {
    print("** $scorerName **\n");
    print("--------------------------------------------------------------------------\n");

    # precision and recall numerators and denominators
    my %idenTotals = (recallDen => 0, recallNum => 0, precisionDen => 0, precisionNum => 0);
    my ($recallNum, $recallDen, $precisionNum, $precisionDen) = (0,0,0,0);

    # iterate over common files between key and response
    my %sourceFileSet = map { $_ => 1 } (keys %keyFileEntities, keys %responseFileEntities);
    foreach my $source_name (sort(keys %sourceFileSet)) {
        print("Source file: $source_name\n");

        # transform entity lists into input for scorers
        my ($keyChains, $responseChains) = CorScorer::IdentifMentions(
            $keyFileEntities{$source_name},
            $responseFileEntities{$source_name},
            \%idenTotals);
        
        # invoke scorer to get precision and recall numerators and denominators for this file
        my ($nr, $dr, $np, $dp) = $scorers{$scorerName}->($keyChains, $responseChains);

        # update global precision and recall numerators and denominators
        $recallNum += $nr;
        $recallDen += $dr;
        $precisionNum += $np;
        $precisionDen += $dp;
    }

    # store the arguments to CorScorer::ShowRPF so that results can be printed together at the end
    my @showRPFargs = ($recallNum, $recallDen, $precisionNum, $precisionDen);
    $scorerResults{$scorerName} = \@showRPFargs;
}

# print out scores
foreach my $scorerName (@scorerNames) {
    print("** $scorerName **\n");
    CorScorer::ShowRPF(@{$scorerResults{$scorerName}});
}
