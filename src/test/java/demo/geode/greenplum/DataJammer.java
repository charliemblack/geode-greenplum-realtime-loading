/*
 * Copyright 2017 Charlie Black
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package demo.geode.greenplum;

import org.apache.geode.cache.Region;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Resource;
import java.util.Date;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {ConfigureGeode.class})
@Ignore("Its more of a debugger")
public class DataJammer {
    @Resource
    private Region testRegion;

    @Test
    public void insertAWholeBunch() {
        int counter = 1;
        while (true) {
            testRegion.put(counter % 3, new SampleData(counter++, new Date(),"some text" + counter++, counter % 2 == 0));
        }
    }
}
