package searchengine.services.utils;

import org.apache.commons.lang3.StringUtils;
import searchengine.dto.SentenceLemma;
import searchengine.services.interfaces.LemmaService;

import java.text.BreakIterator;
import java.util.*;

public class SentenceUtil {
    private static final int LIMIT_SENTENCE_LENGTH = 120;

    public static String limitSentence(String sentence) {
        int length = sentence.length();

        if (length <= LIMIT_SENTENCE_LENGTH)
            return sentence;

        int startLemma = sentence.indexOf("<b>");
        int endLemma = sentence.indexOf("</b>") + 4;

        if (endLemma - startLemma > LIMIT_SENTENCE_LENGTH)
            return "..." + sentence.substring(startLemma, endLemma) + "...";

        int remainLength = LIMIT_SENTENCE_LENGTH - (endLemma - startLemma) / 2;

        int start = startLemma - remainLength < 0
                ? 0
                : sentence.indexOf(" ", startLemma - remainLength) + 1;
        int end = endLemma + remainLength > length
                ? length
                : sentence.substring(endLemma, endLemma + remainLength).lastIndexOf(" ") + endLemma;

        String cropped = sentence.substring(start, end);
        if (start > 0)
            cropped = "..." + cropped;
        if (end < length)
            cropped += "...";

        return cropped;
    }

    public static SentenceLemma findLemmasInSentence(
            LemmaService lemmaService,
            String sentence,
            Map<String, Double> lemmaFrequency) {

        Map<String, Double> lemmasInSentence = new HashMap<>();
        String[] words = Arrays.stream(lemmaService.splitToWords(sentence)).distinct().toArray(String[]::new);

        for (String word : words) {
            String lemma = lemmaService.getFirstNormalForm(word);
            if (!lemma.isBlank() && lemmaFrequency.containsKey(lemma)) {
                lemmasInSentence.put(lemma, lemmaFrequency.get(lemma));
                sentence = StringUtils.replaceIgnoreCase(sentence, word, "<b>" + word + "</b>");
            }
        }

        SentenceLemma sentenceLemma = new SentenceLemma();
        sentenceLemma.setText(sentence);
        sentenceLemma.setLemmaFrequency(lemmasInSentence);

        return sentenceLemma;
    }

    public static List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();

        BreakIterator iterator = BreakIterator.getSentenceInstance();
        iterator.setText(text);

        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            sentences.add(text.substring(start, end));
        }

        return sentences;
    }

    public static List<String> sortSentencesByLemmaRarityAndCount(List<SentenceLemma> sentenceLemmaList) {
        return sentenceLemmaList.stream()
                .sorted(sentenceComparator())
                .map(SentenceLemma::getText)
                .toList();
    }

    private static Comparator<SentenceLemma> sentenceComparator() {
        Comparator<SentenceLemma> compareByLemmaRarity = (o1, o2) -> {
            Map<String, Double> lemmaFrequency1 = o1.getLemmaFrequency();
            Map<String, Double> lemmaFrequency2 = o2.getLemmaFrequency();

            List<Double> sortedFrequencies1 = new ArrayList<>(lemmaFrequency1.values());
            List<Double> sortedFrequencies2 = new ArrayList<>(lemmaFrequency2.values());

            Collections.sort(sortedFrequencies1);
            Collections.sort(sortedFrequencies2);

            Iterator<Double> iterator1 = sortedFrequencies1.iterator();
            Iterator<Double> iterator2 = sortedFrequencies2.iterator();

            Double mostRareLemma1;
            Double mostRareLemma2;

            while (iterator1.hasNext() && iterator2.hasNext()) {
                mostRareLemma1 = iterator1.next();
                mostRareLemma2 = iterator2.next();

                int compare = Double.compare(mostRareLemma1, mostRareLemma2);
                if (compare != 0)
                    return compare;
            }

            return 0;
        };

        return compareByLemmaRarity.thenComparing(
                dto -> dto.getLemmaFrequency().size(),
                Comparator.reverseOrder());
    }
}
