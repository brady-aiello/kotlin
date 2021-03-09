/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "SingleThreadMarkAndSweep.hpp"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "../GlobalData.hpp"
#include "../ObjectOps.hpp"
#include "../TestSupport.hpp"
#include "ObjectTestSupport.hpp"

using namespace kotlin;

// These tests can only work if `GC` is `SingleThreadMarkAndSweep`.
// TODO: Extracting GC into a separate module will help with this.

namespace {

struct Payload {
    ObjHeader* field1;
    ObjHeader* field2;
    ObjHeader* field3;

    static constexpr std::array kFields = {
            &Payload::field1,
            &Payload::field2,
            &Payload::field3,
    };
};

test_support::TypeInfoHolder typeHolder{test_support::TypeInfoHolder::ObjectBuilder<Payload>()};

class Global : private Pinned {
public:
    explicit Global(mm::ThreadData& threadData) {
        mm::GlobalsRegistry::Instance().RegisterStorageForGlobal(&threadData, &location_);
        mm::AllocateObject(&threadData, typeHolder.typeInfo(), &location_);
    }

    ObjHeader* header() { return location_; }

    test_support::Object<Payload>& operator*() { return test_support::Object<Payload>::FromObjHeader(location_); }

private:
    ObjHeader* location_;
};

class Stack : private Pinned {
public:
    explicit Stack(mm::ThreadData& threadData) { mm::AllocateObject(&threadData, typeHolder.typeInfo(), holder_.slot()); }

    ObjHeader* header() { return holder_.obj(); }

    test_support::Object<Payload>& operator*() { return test_support::Object<Payload>::FromObjHeader(holder_.obj()); }

private:
    ObjHolder holder_;
};

class SingleThreadMarkAndSweepTest : public testing::Test {
public:
    ~SingleThreadMarkAndSweepTest() { mm::GlobalsRegistry::Instance().ClearForTests(); }

    KStdVector<ObjHeader*> Alive() {
        KStdVector<ObjHeader*> objects;
        for (auto node : mm::GlobalData::Instance().objectFactory().Iter()) {
            objects.push_back(node.IsArray() ? node.GetArrayHeader()->obj() : node.GetObjHeader());
        }
        return objects;
    }
};

} // namespace

TEST_F(SingleThreadMarkAndSweepTest, Basic) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        Global global1{threadData};
        Global global2{threadData};
        Stack stack1{threadData};
        Stack stack2{threadData};

        threadData.gc().PerformFullGC();
    });
}
