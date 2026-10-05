package com.ademola.esm.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemoDataSeederTest {

    @Test
    void refusesToSeedWithoutAStrongDemoPassword() {
        for (String weak : new String[] {null, "", "short"}) {
            DemoDataSeeder seeder = new DemoDataSeeder(
                    new DemoProperties(weak), null, null, null, null, null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> seeder.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESM_DEMO_PASSWORD");
        }
    }
}
