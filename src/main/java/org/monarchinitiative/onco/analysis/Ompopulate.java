package org.monarchinitiative.onco.analysis;

import com.google.common.collect.ImmutableMap;
import de.charite.compbio.jannovar.data.*;
import de.charite.compbio.jannovar.htsjdk.VariantContextAnnotator;
import de.charite.compbio.jannovar.progress.ProgressReporter;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.monarchinitiative.onco.data.CivicParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.monarchinitiative.onco.analysis.FilePaths.civicLocalPath;

public class Ompopulate {
    private final static Logger logger = LoggerFactory.getLogger(Ompopulate.class);
    private final JannovarData jannovarData;
    /**
     * Reference dictionary that is part of {@link #jannovarData}.
     */
    private final ReferenceDictionary referenceDictionary;
    /**
     * Map of Chromosomes, used in the annotation.
     */
    private final ImmutableMap<Integer, Chromosome> chromosomeMap;
    /**
     * A Jannovar object to report progress of VCF parsing.
     */
    private ProgressReporter progressReporter = null;
    private final String vcfFilePath;

    //GenomeAssembly genomeAssembly = GenomeA
    /**
     * Number of variants that were not filtered.
     */
    private int n_good_quality_variants = 0;
    /**
     * Number of variants that were removed because of the quality filter.
     */
    private int n_filtered_variants = 0;

    /**
     * Number of samples in the VCF file.
     */
    private int n_samples;
    /**
     * Name of the proband in the VCF file.
     */
    private String samplename;
    /**
     * List of all names in the VCF file
     */
    private List<String> samplenames;
    private Map<ChrPosition, List<CivicVariant>> pos2civis;


    public Ompopulate(String jannovarPath, String vcfPath) {
        try {
            this.jannovarData = new JannovarDataSerializer(jannovarPath).load();
        } catch (SerializationException se) {
            throw new RuntimeException(se.getMessage());
        }
        File f = new File(vcfPath);
        if (!f.exists()) {
            throw new RuntimeException("Could not find VCF file at " + vcfPath);
        }
        this.referenceDictionary = jannovarData.getRefDict();
        this.chromosomeMap = jannovarData.getChromosomes();
        this.vcfFilePath = f.getAbsolutePath();
        parseCivic();
        parseVcf();
    }


    private void parseCivic() {
        CivicParser parser = new CivicParser(civicLocalPath);
        List<CivicVariant> vars = parser.getVarlist();
        this.pos2civis = new HashMap<>();
        int c = 0;
        for (CivicVariant var : vars) {
            ChrPosition pos = new ChrPosition(var.getChromosome(), var.getStart(), var.getEnd());
            pos2civis.putIfAbsent(pos, new ArrayList<>());
            List<CivicVariant> vvlist = pos2civis.get(pos);
            vvlist.add(var);
            c++;
        }
        System.out.printf("[INFO] Got %d CIVIC variants.\n", c);
    }


    private void parseVcf() {
        // whether or not to just look at a specific genomic interval
        final boolean useInterval = false;
        final long startTime = System.nanoTime();

        try (VCFFileReader vcfReader = new VCFFileReader(new File(this.vcfFilePath), useInterval)) {
            //final SAMSequenceDictionary seqDict = VCFFileReader.getSequenceDictionary(new File(getOptionalVcfPath));
            VCFHeader vcfHeader = vcfReader.getFileHeader();
            this.samplenames = vcfHeader.getSampleNamesInOrder();
            this.n_samples = samplenames.size();
            this.samplename = samplenames.get(0);
            logger.trace("Annotating VCF at " + this.vcfFilePath + " for sample " + this.samplename);
            CloseableIterator<VariantContext> iter = vcfReader.iterator();
            VariantContextAnnotator variantEffectAnnotator =
                    new VariantContextAnnotator(this.referenceDictionary, this.chromosomeMap,
                            new VariantContextAnnotator.Options());
            // Note that we do not use Genomiser data in this version of LIRICAL
            // Therefore, just pass in an empty list to satisfy the API
            // List<RegulatoryFeature> emtpylist = ImmutableList.of();
            //  ChromosomalRegionIndex<RegulatoryFeature> emptyRegionIndex = ChromosomalRegionIndex.of(emtpylist);
            //  JannovarVariantAnnotator jannovarVariantAnnotator = new JannovarVariantAnnotator(genomeAssembly, jannovarData, emptyRegionIndex);
            while (iter.hasNext()) {
                VariantContext vc = iter.next();
                if (vc.isFiltered()) {
                    // this is a failing VariantContext
                    n_filtered_variants++;
                    continue;
                } else {
                    n_good_quality_variants++;
                }
                vc = variantEffectAnnotator.annotateVariantContext(vc);
                List<Allele> altAlleles = vc.getAlternateAlleles();
                String contig = vc.getContig();
                int start = vc.getStart();
                String ref = vc.getReference().getBaseString();
                for (int i = 0; i < altAlleles.size(); i++) {
                    Allele allele = altAlleles.get(i);
                    String alt = allele.getBaseString();
                    // Map<String, SampleGenotype> sampleGenotypes = createAlleleSampleGenotypes(vc, i);
                    int end = start + alt.length() - 1;
                    ChrPosition pos = new ChrPosition(contig, start, end);
                    if (this.pos2civis.containsKey(pos)) {
                        List<CivicVariant> vlist = this.pos2civis.get(pos);
                        for (CivicVariant cvar : vlist) {
                            if (ref.equals(cvar.getReferences_bases()) && alt.equals(cvar.getVariant_bases())) {
                                System.out.println("[EXACT MATCH}:");
                                System.out.println("\t" + cvar);
                            } else {
                                System.out.println("[OVERLAPPING}:");
                                System.out.println("\t" + cvar);
                            }
                        }
                        System.out.println(this.pos2civis.get(pos));
                    }


                }
            }
        }
        System.out.printf("[INFO] Got %d variants and filtered out %d.\n", n_good_quality_variants, n_filtered_variants);
    }


}