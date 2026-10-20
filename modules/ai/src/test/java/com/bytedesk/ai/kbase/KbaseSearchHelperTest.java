package com.bytedesk.ai.kbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.bytedesk.ai.robot.RobotLlm;
import com.bytedesk.ai.robot.RobotProtobuf;
import com.bytedesk.ai.service.SearchResultWithSources;
import com.bytedesk.core.message.content.RobotContent;
import com.bytedesk.kbase.article.ArticleRestService;
import com.bytedesk.kbase.article.elastic.ArticleElasticService;
import com.bytedesk.kbase.article.vector.ArticleVectorService;
import com.bytedesk.kbase.llm_chunk.ChunkRestService;
import com.bytedesk.kbase.llm_chunk.elastic.ChunkElasticService;
import com.bytedesk.kbase.llm_chunk.vector.ChunkVectorService;
import com.bytedesk.kbase.llm_faq.FaqProtobuf;
import com.bytedesk.kbase.llm_faq.FaqRestService;
import com.bytedesk.kbase.llm_faq.elastic.FaqElasticService;
import com.bytedesk.kbase.llm_faq.vector.FaqVectorService;
import com.bytedesk.kbase.llm_feishu.elastic.FeishuDocElasticService;
import com.bytedesk.kbase.llm_feishu.vector.FeishuDocVectorService;
import com.bytedesk.kbase.llm_text.TextRestService;
import com.bytedesk.kbase.llm_text.elastic.TextElasticService;
import com.bytedesk.kbase.llm_text.vector.TextVectorService;
import com.bytedesk.kbase.llm_webpage.WebpageRestService;
import com.bytedesk.kbase.llm_webpage.elastic.WebpageElasticService;
import com.bytedesk.kbase.llm_webpage.vector.WebpageVectorService;

class KbaseSearchHelperTest {

    @Test
    void rerankKeepsTheScoreOfTheOriginalSourceInsteadOfCreatingAZeroScorePlaceholder() {
        KbaseSearchHelper helper = newHelper();
        FaqProtobuf faq = FaqProtobuf.builder()
                .uid("faq-row-1")
                .sourceUid("document-1")
                .question("退款多久到账")
                .answer("退款审核通过后原路退回")
                .build();
        RobotContent.SourceReference source = RobotContent.SourceReference.builder()
                .sourceType(RobotContent.SourceTypeEnum.FAQ)
                .sourceUid("document-1")
                .sourceName("退款多久到账")
                .contentSummary("退款审核通过后原路退回")
                .score(0.91)
                .build();

        SearchResultWithSources reranked = helper.rerankMergeTopK(
                new SearchResultWithSources(List.of(faq), List.of(source)),
                RobotProtobuf.builder().llm(RobotLlm.builder().topK(1).build()).build());

        assertEquals(1, reranked.getSearchResults().size());
        assertEquals(1, reranked.getSourceReferences().size());
        assertEquals("document-1", reranked.getSourceReferences().get(0).getSourceUid());
        assertEquals(0.91, reranked.getSourceReferences().get(0).getScore(), 0.001);
    }

    private static KbaseSearchHelper newHelper() {
        return new KbaseSearchHelper(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                mock(FaqElasticService.class), mock(TextElasticService.class), mock(ChunkElasticService.class),
                mock(WebpageElasticService.class), mock(ArticleElasticService.class),
                mock(FaqRestService.class), mock(TextRestService.class), mock(ChunkRestService.class),
                mock(WebpageRestService.class), mock(ArticleRestService.class),
                emptyProvider(), mock(FeishuDocElasticService.class));
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
