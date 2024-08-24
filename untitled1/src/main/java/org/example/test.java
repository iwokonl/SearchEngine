package org.example;
import morfologik.stemming.IStemmer;
import morfologik.stemming.WordData;
import morfologik.stemming.polish.PolishStemmer;

import java.util.List;

public class test {
    public static void main(String[] args) {
        IStemmer stemmer = new PolishStemmer();
        String word = "bułki";
        List<WordData> stems = stemmer.lookup(word);
//        System.out.println(stems.contains(wychodzić));
        for (WordData stem : stems) {

            System.out.println("Base form: " + stem.getStem() + ", Tag: " + stem.getTag());
        }

    }
}
