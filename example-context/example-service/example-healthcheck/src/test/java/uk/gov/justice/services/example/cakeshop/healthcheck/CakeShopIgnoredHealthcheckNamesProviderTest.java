package uk.gov.justice.services.example.cakeshop.healthcheck;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.gov.justice.services.healthcheck.healthchecks.JobStoreHealthcheck.JOB_STORE_HEALTHCHECK_NAME;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CakeShopIgnoredHealthcheckNamesProviderTest {

    @InjectMocks
    private CakeShopIgnoredHealthcheckNamesProvider ignoredHealthcheckNamesProvider;

    @Test
    public void shouldGetListOfAllHealthchecksToIgnore() throws Exception {

        final List<String> ignoredHealthChecks = ignoredHealthcheckNamesProvider.getNamesOfIgnoredHealthChecks();

        assertThat(ignoredHealthChecks.size(), is(1));
        assertThat(ignoredHealthChecks.get(0), is(JOB_STORE_HEALTHCHECK_NAME));
    }
}